/*
 * Autopsy Forensic Browser
 * 
 * Copyright 2012-2014 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.ingest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.DerivedFile;
import org.sleuthkit.datamodel.Directory;
import org.sleuthkit.datamodel.File;
import org.sleuthkit.datamodel.FileSystem;
import org.sleuthkit.datamodel.LayoutFile;
import org.sleuthkit.datamodel.LocalFile;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;
import org.sleuthkit.datamodel.VirtualDirectory;

final class IngestJobScheduler {

    private static final Logger logger = Logger.getLogger(IngestJobScheduler.class.getName());
    private static final int FAT_NTFS_FLAGS = TskData.TSK_FS_TYPE_ENUM.TSK_FS_TYPE_FAT12.getValue() | TskData.TSK_FS_TYPE_ENUM.TSK_FS_TYPE_FAT16.getValue() | TskData.TSK_FS_TYPE_ENUM.TSK_FS_TYPE_FAT32.getValue() | TskData.TSK_FS_TYPE_ENUM.TSK_FS_TYPE_NTFS.getValue();
    private static IngestJobScheduler instance = new IngestJobScheduler();
    private final AtomicLong nextIngestJobId = new AtomicLong(0L);
    private final ConcurrentHashMap<Long, IngestJob> ingestJobsById = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<DataSourceIngestTask> dataSourceTasks = new LinkedBlockingQueue<>();
    private final TreeSet<FileIngestTask> rootDirectoryTasks = new TreeSet<>(new RootDirectoryTaskComparator());
    private final List<FileIngestTask> directoryTasks = new ArrayList<>();
    private final LinkedBlockingQueue<FileIngestTask> fileTasks = new LinkedBlockingQueue<>();
    private final DataSourceIngestTaskQueue dataSourceIngestTaskQueue = new DataSourceIngestTaskQueue();
    private final FileIngestTaskQueue fileIngestTaskQueue = new FileIngestTaskQueue();

    static IngestJobScheduler getInstance() {
        return instance;
    }

    private IngestJobScheduler() {
    }

    /**
     * Creates an ingest job for a data source.
     *
     * @param rootDataSource The data source to ingest.
     * @param ingestModuleTemplates The ingest module templates to use to create
     * the ingest pipelines for the job.
     * @param processUnallocatedSpace Whether or not the job should include
     * processing of unallocated space.
     * @return A collection of ingest module start up errors, empty on success.
     * @throws InterruptedException
     */
    synchronized List<IngestModuleError> startIngestJob(Content dataSource, List<IngestModuleTemplate> ingestModuleTemplates, boolean processUnallocatedSpace) throws InterruptedException {
        long jobId = nextIngestJobId.incrementAndGet();
        IngestJob job = new IngestJob(jobId, dataSource, ingestModuleTemplates, processUnallocatedSpace);
        ingestJobsById.put(jobId, job);
        IngestManager.getInstance().fireIngestJobStarted(jobId);
        List<IngestModuleError> errors = job.startUp();
        if (errors.isEmpty()) {
            addDataSourceToIngestJob(job, dataSource);
        } else {
            ingestJobsById.remove(jobId);
            IngestManager.getInstance().fireIngestJobCancelled(jobId);
        }
        return errors;
    }

    boolean ingestJobsAreRunning() {
        for (IngestJob job : ingestJobsById.values()) {
            if (!job.isCancelled()) {
                return true;
            }
        }
        return false;
    }

    synchronized void addDataSourceToIngestJob(IngestJob job, Content dataSource) throws InterruptedException {
        // If the thread executing this code is interrupted, it is because the 
        // the number of ingest threads has been decreased while ingest jobs are 
        // running. The calling thread will exit in an orderly fashion, but the 
        // task still needs to be enqueued rather than lost.
        DataSourceIngestTask task = new DataSourceIngestTask(job, dataSource);
        while (true) {
            try {
                dataSourceTasks.put(task);
                break;
            } catch (InterruptedException ex) {
                // Reset the interrupted status of the thread so the orderly
                // exit can occur in the intended place.
                Thread.currentThread().interrupt();
            }
        }

        Collection<AbstractFile> rootObjects = dataSource.accept(new GetRootDirectoryVisitor());
        List<AbstractFile> firstLevelFiles = new ArrayList<>();
        if (rootObjects.isEmpty() && dataSource instanceof AbstractFile) {
            // The data source is file.
            firstLevelFiles.add((AbstractFile) dataSource);
        } else {
            for (AbstractFile root : rootObjects) {
                List<Content> children;
                try {
                    children = root.getChildren();
                    if (children.isEmpty()) {
                        //add the root itself, could be unalloc file, child of volume or image
                        firstLevelFiles.add(root);
                    } else {
                        //root for fs root dir, schedule children dirs/files
                        for (Content child : children) {
                            if (child instanceof AbstractFile) {
                                firstLevelFiles.add((AbstractFile) child);
                            }
                        }
                    }
                } catch (TskCoreException ex) {
                    logger.log(Level.WARNING, "Could not get children of root to enqueue: " + root.getId() + ": " + root.getName(), ex); //NON-NLS
                }
            }
        }
        for (AbstractFile firstLevelFile : firstLevelFiles) {
            FileIngestTask fileTask = new FileIngestTask(job, firstLevelFile);
            if (shouldEnqueueFileTask(fileTask)) {
                rootDirectoryTasks.add(fileTask);
                fileTask.getIngestJob().notifyTaskAdded();
            }
        }

        // Reshuffle/update the dir and file level queues if needed
        updateFileTaskQueues();
    }

    synchronized void addFileToIngestJob(IngestJob job, AbstractFile file) { // RJCTODO: Just one at a time?
        FileIngestTask task = new FileIngestTask(job, file);
        if (shouldEnqueueFileTask(task)) {
            addTaskToFileQueue(task);
        }        
    }

    private synchronized void updateFileTaskQueues() throws InterruptedException {
        // we loop because we could have a directory that has all files
        // that do not get enqueued
        while (true) {
            // There are files in the queue, we're done
            if (fileTasks.isEmpty() == false) {
                return;
            }
            // fill in the directory queue if it is empty.
            if (this.directoryTasks.isEmpty()) {
                // bail out if root is also empty -- we are done
                if (rootDirectoryTasks.isEmpty()) {
                    return;
                }
                FileIngestTask rootTask = rootDirectoryTasks.pollFirst();
                directoryTasks.add(rootTask);
            }
            //pop and push AbstractFile directory children if any
            //add the popped and its leaf children onto cur file list
            FileIngestTask parentTask = directoryTasks.remove(directoryTasks.size() - 1);
            final AbstractFile parentFile = parentTask.getFile();
            // add itself to the file list
            if (shouldEnqueueFileTask(parentTask)) {
                addTaskToFileQueue(parentTask);
            }
            // add its children to the file and directory lists
            try {
                List<Content> children = parentFile.getChildren();
                for (Content c : children) {
                    if (c instanceof AbstractFile) {
                        AbstractFile childFile = (AbstractFile) c;
                        FileIngestTask childTask = new FileIngestTask(parentTask.getIngestJob(), childFile);
                        if (childFile.hasChildren()) {
                            directoryTasks.add(childTask);
                            childTask.getIngestJob().notifyTaskAdded();
                        } else if (shouldEnqueueFileTask(childTask)) {
                            addTaskToFileQueue(childTask);
                        }
                    }
                }
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, "Could not get children of file and update file queues: " + parentFile.getName(), ex);
            }
        }
    }
    
    private void addTaskToFileQueue(FileIngestTask task) {
        // If the thread executing this code is interrupted, it is because the 
        // the number of ingest threads has been decreased while ingest jobs are 
        // running. The calling thread will exit in an orderly fashion, but the 
        // task still needs to be enqueued rather than lost.
        while (true) {
            try {
                fileTasks.put(task);
                break;
            } catch (InterruptedException ex) {
                // Reset the interrupted status of the thread so the orderly
                // exit can occur in the intended place.
                Thread.currentThread().interrupt();
            }
        }
    }
        
    /**
     * Check if the file is a special file that we should skip
     *
     * @param processTask a task whose file to check if should be queued of
     * skipped
     * @return true if should be enqueued, false otherwise
     */
    private static boolean shouldEnqueueFileTask(final FileIngestTask processTask) {
        final AbstractFile aFile = processTask.getFile();
        //if it's unalloc file, skip if so scheduled
        if (processTask.getIngestJob().shouldProcessUnallocatedSpace() == false && aFile.getType().equals(TskData.TSK_DB_FILES_TYPE_ENUM.UNALLOC_BLOCKS)) {
            return false;
        }
        String fileName = aFile.getName();
        if (fileName.equals(".") || fileName.equals("..")) {
            return false;
        } else if (aFile instanceof org.sleuthkit.datamodel.File) {
            final org.sleuthkit.datamodel.File f = (File) aFile;
            //skip files in root dir, starting with $, containing : (not default attributes)
            //with meta address < 32, i.e. some special large NTFS and FAT files
            FileSystem fs = null;
            try {
                fs = f.getFileSystem();
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, "Could not get FileSystem for " + f, ex); //NON-NLS
            }
            TskData.TSK_FS_TYPE_ENUM fsType = TskData.TSK_FS_TYPE_ENUM.TSK_FS_TYPE_UNSUPP;
            if (fs != null) {
                fsType = fs.getFsType();
            }
            if ((fsType.getValue() & FAT_NTFS_FLAGS) == 0) {
                //not fat or ntfs, accept all files
                return true;
            }
            boolean isInRootDir = false;
            try {
                isInRootDir = f.getParentDirectory().isRoot();
            } catch (TskCoreException ex) {
                logger.log(Level.WARNING, "Could not check if should enqueue the file: " + f.getName(), ex); //NON-NLS
            }
            if (isInRootDir && f.getMetaAddr() < 32) {
                String name = f.getName();
                if (name.length() > 0 && name.charAt(0) == '$' && name.contains(":")) {
                    return false;
                }
            } else {
                return true;
            }
        }
        return true;
    }

    void cancelAllIngestJobs() {
        for (IngestJob job : ingestJobsById.values()) {
            job.cancel();
        }
    }

    IngestTaskQueue getDataSourceIngestTaskQueue() {
        return dataSourceIngestTaskQueue;
    }

    IngestTaskQueue getFileIngestTaskQueue() {
        return fileIngestTaskQueue;
    }

    /**
     * Finds top level objects such as file system root directories, layout
     * files and virtual directories.
     */
    private static class GetRootDirectoryVisitor extends GetFilesContentVisitor {

        @Override
        public Collection<AbstractFile> visit(VirtualDirectory ld) {
            //case when we hit a layout directoryor local file container, not under a real FS
            //or when root virt dir is scheduled
            Collection<AbstractFile> ret = new ArrayList<>();
            ret.add(ld);
            return ret;
        }

        @Override
        public Collection<AbstractFile> visit(LayoutFile lf) {
            //case when we hit a layout file, not under a real FS
            Collection<AbstractFile> ret = new ArrayList<>();
            ret.add(lf);
            return ret;
        }

        @Override
        public Collection<AbstractFile> visit(Directory drctr) {
            //we hit a real directory, a child of real FS
            Collection<AbstractFile> ret = new ArrayList<>();
            ret.add(drctr);
            return ret;
        }

        @Override
        public Collection<AbstractFile> visit(FileSystem fs) {
            return getAllFromChildren(fs);
        }

        @Override
        public Collection<AbstractFile> visit(File file) {
            //can have derived files
            return getAllFromChildren(file);
        }

        @Override
        public Collection<AbstractFile> visit(DerivedFile derivedFile) {
            //can have derived files
            //TODO test this and overall scheduler with derived files
            return getAllFromChildren(derivedFile);
        }

        @Override
        public Collection<AbstractFile> visit(LocalFile localFile) {
            //can have local files
            //TODO test this and overall scheduler with local files
            return getAllFromChildren(localFile);
        }
    }

    private static class RootDirectoryTaskComparator implements Comparator<FileIngestTask> {

        @Override
        public int compare(FileIngestTask q1, FileIngestTask q2) {
            AbstractFilePriority.Priority p1 = AbstractFilePriority.getPriority(q1.getFile());
            AbstractFilePriority.Priority p2 = AbstractFilePriority.getPriority(q2.getFile());
            if (p1 == p2) {
                return (int) (q2.getFile().getId() - q1.getFile().getId());
            } else {
                return p2.ordinal() - p1.ordinal();
            }
        }

        private static class AbstractFilePriority {

            enum Priority {

                LAST, LOW, MEDIUM, HIGH
            }
            static final List<Pattern> LAST_PRI_PATHS = new ArrayList<>();
            static final List<Pattern> LOW_PRI_PATHS = new ArrayList<>();
            static final List<Pattern> MEDIUM_PRI_PATHS = new ArrayList<>();
            static final List<Pattern> HIGH_PRI_PATHS = new ArrayList<>();
            /* prioritize root directory folders based on the assumption that we are
             * looking for user content. Other types of investigations may want different
             * priorities. */

            static /* prioritize root directory folders based on the assumption that we are
             * looking for user content. Other types of investigations may want different
             * priorities. */ {
                // these files have no structure, so they go last
                //unalloc files are handled as virtual files in getPriority()
                //LAST_PRI_PATHS.schedule(Pattern.compile("^\\$Unalloc", Pattern.CASE_INSENSITIVE));
                //LAST_PRI_PATHS.schedule(Pattern.compile("^\\Unalloc", Pattern.CASE_INSENSITIVE));
                LAST_PRI_PATHS.add(Pattern.compile("^pagefile", Pattern.CASE_INSENSITIVE));
                LAST_PRI_PATHS.add(Pattern.compile("^hiberfil", Pattern.CASE_INSENSITIVE));
                // orphan files are often corrupt and windows does not typically have
                // user content, so put them towards the bottom
                LOW_PRI_PATHS.add(Pattern.compile("^\\$OrphanFiles", Pattern.CASE_INSENSITIVE));
                LOW_PRI_PATHS.add(Pattern.compile("^Windows", Pattern.CASE_INSENSITIVE));
                // all other files go into the medium category too
                MEDIUM_PRI_PATHS.add(Pattern.compile("^Program Files", Pattern.CASE_INSENSITIVE));
                // user content is top priority
                HIGH_PRI_PATHS.add(Pattern.compile("^Users", Pattern.CASE_INSENSITIVE));
                HIGH_PRI_PATHS.add(Pattern.compile("^Documents and Settings", Pattern.CASE_INSENSITIVE));
                HIGH_PRI_PATHS.add(Pattern.compile("^home", Pattern.CASE_INSENSITIVE));
                HIGH_PRI_PATHS.add(Pattern.compile("^ProgramData", Pattern.CASE_INSENSITIVE));
            }

            /**
             * Get the scheduling priority for a given file.
             *
             * @param abstractFile
             * @return
             */
            static AbstractFilePriority.Priority getPriority(final AbstractFile abstractFile) {
                if (!abstractFile.getType().equals(TskData.TSK_DB_FILES_TYPE_ENUM.FS)) {
                    //quickly filter out unstructured content
                    //non-fs virtual files and dirs, such as representing unalloc space
                    return AbstractFilePriority.Priority.LAST;
                }
                //determine the fs files priority by name
                final String path = abstractFile.getName();
                if (path == null) {
                    return AbstractFilePriority.Priority.MEDIUM;
                }
                for (Pattern p : HIGH_PRI_PATHS) {
                    Matcher m = p.matcher(path);
                    if (m.find()) {
                        return AbstractFilePriority.Priority.HIGH;
                    }
                }
                for (Pattern p : MEDIUM_PRI_PATHS) {
                    Matcher m = p.matcher(path);
                    if (m.find()) {
                        return AbstractFilePriority.Priority.MEDIUM;
                    }
                }
                for (Pattern p : LOW_PRI_PATHS) {
                    Matcher m = p.matcher(path);
                    if (m.find()) {
                        return AbstractFilePriority.Priority.LOW;
                    }
                }
                for (Pattern p : LAST_PRI_PATHS) {
                    Matcher m = p.matcher(path);
                    if (m.find()) {
                        return AbstractFilePriority.Priority.LAST;
                    }
                }
                //default is medium
                return AbstractFilePriority.Priority.MEDIUM;
            }
        }
    }

    private class DataSourceIngestTaskQueue implements IngestTaskQueue {

        @Override
        public IngestTask getNextTask() throws InterruptedException {
            return dataSourceTasks.take();
        }
    }

    private class FileIngestTaskQueue implements IngestTaskQueue {

        @Override
        public IngestTask getNextTask() throws InterruptedException {
            FileIngestTask task = fileTasks.take();
            updateFileTaskQueues();
            return task;
        }
    }
}
