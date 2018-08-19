/*
 * Copyright 2016-2018 Veselin Markov
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
 * 
 * Created on 10.12.2016
 */
package org.eclipse.egit.ui.internal.actions;

import static java.util.Arrays.spliterator;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCacheEntry;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffChangedListener;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffData;
import org.eclipse.egit.core.op.DeletePathsOperation;
import org.eclipse.egit.core.op.DiscardChangesOperation;
import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.JobFamilies;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.Repository;

/**
 * @author markov Created on: 10.12.2016
 */
public class ResetConfigWithHead extends DiscardChangesActionHandler {

	volatile Map<IndexDiffChangedListenerImplementation, IndexDiffCacheEntry> cachedEntries = new HashMap<>();

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		Repository[] repositories = getRepositories(event);

		List<IndexDiffCacheEntry> diffChaches = stream(spliterator(repositories), false).map(this::toDiffCache).collect(toList());

		String jobname = UIText.DiscardChangesAction_discardChanges;
		Job job = new WorkspaceJobExtension(jobname, diffChaches);
		job.setUser(true);
		// job.setRule(operation.getSchedulingRule());
		job.schedule();
		return null;
	}

	private IndexDiffCacheEntry toDiffCache(Repository repository) {
		IndexDiffChangedListenerImplementation listener = new IndexDiffChangedListenerImplementation();
		IndexDiffCacheEntry indexDiffCacheEntry = new IndexDiffCacheEntry(repository, listener);
		cachedEntries.put(listener, indexDiffCacheEntry);
		return indexDiffCacheEntry;
	}

	/**
	 * @author markov Created on: 10.12.2016
	 */
	private final class WorkspaceJobExtension extends WorkspaceJob {

		private List<IndexDiffCacheEntry> diffChaches;

		private WorkspaceJobExtension(String name, List<IndexDiffCacheEntry> asyncDiffChaches) {
			super(name);
			this.diffChaches = asyncDiffChaches;
		}

		@Override
		public IStatus runInWorkspace(IProgressMonitor monitor) {
			try {
				for (IndexDiffCacheEntry diff : diffChaches) {
					IndexDiffChangedListenerImplementation l = getListener(diff);
					if (l == null)
						throw new CoreException(new Status(Status.ERROR, "de.markov.egit.resetter", "Kein Listener für die Gir-Repo gefunden"));
					try {

						IndexDiffData diffs = l.getDiffs();
						Repository repository = l.repository;
						File workTree = repository.getWorkTree();
						resetConfigFiles(workTree, diffs, monitor);
					} catch (InterruptedException e) {
						throw new CoreException(Status.CANCEL_STATUS);
					}
				}

			} catch (CoreException e) {
				return Activator.createErrorStatus(e.getStatus().getMessage(), e);
			}
			return Status.OK_STATUS;
		}

		/**
		 * @param indexDiffCacheEntry
		 * @return den ReadDoneListener für die ChacheEntry oder null
		 */
		@Nullable
		protected IndexDiffChangedListenerImplementation getListener(IndexDiffCacheEntry indexDiffCacheEntry) {

			Optional<IndexDiffChangedListenerImplementation> findAny = cachedEntries.keySet().stream().filter(l -> cachedEntries.get(l) == indexDiffCacheEntry).findAny();
			if (findAny.isPresent())
				return findAny.get();
			else
				return null;
		}

		private void resetConfigFiles(File workTree, IndexDiffData indexDiffData, IProgressMonitor monitor) throws CoreException {
			Set<String> modifiedFiles = indexDiffData.getModified();
			List<IPath> configFilesPathsToReset = modifiedFiles.stream().filter(this::isConfigFile).map(relativePath -> new File(workTree, relativePath).getAbsolutePath()).map(Path::new).collect(Collectors.toList());
			if(!configFilesPathsToReset.isEmpty()) {
				DiscardChangesOperation discardOperation = new DiscardChangesOperation(configFilesPathsToReset);
				discardOperation.execute(monitor);
			}

			Set<String> untrackedFiles = indexDiffData.getUntracked();
			List<IPath> configFilesPathsToDelete = untrackedFiles.stream().filter(this::isConfigFileToDelete).map(relativePath -> new File(workTree, relativePath).getAbsolutePath()).map(Path::new).collect(Collectors.toList());
			if(!configFilesPathsToDelete.isEmpty()) {
				DeletePathsOperation deleteOperation = new DeletePathsOperation(configFilesPathsToDelete);
				deleteOperation.execute(monitor);
			}
		}

		private boolean isConfigFile(String relativeFilePath) {
			if (relativeFilePath.endsWith(".classpath"))
				return true;
			if (relativeFilePath.endsWith(".project"))
				return true;
			if (relativeFilePath.endsWith("org.eclipse.wst.common.component"))
				return true;
			if (relativeFilePath.endsWith("org.eclipse.jdt.core.prefs"))
				return true;
			if (relativeFilePath.endsWith("org.eclipse.core.resources.prefs"))
				return true;
			if (relativeFilePath.endsWith("org.eclipse.wst.common.project.facet.core.xml"))
				return true;

			return false;
		}

		private boolean isConfigFileToDelete(String relativeFilePath) {
			if (relativeFilePath.endsWith("org.eclipse.wst.validation.prefs"))
				return true;
			if (relativeFilePath.endsWith("org.eclipse.wst.common.project.facet.core.prefs.xml"))
				return true;

			return false;
		}

		@Override
		public boolean belongsTo(Object family) {
			if (JobFamilies.DISCARD_CHANGES.equals(family))
				return true;
			return super.belongsTo(family);
		}
	}

	/**
	 * @author markov Created on: 10.12.2016
	 */
	private final class IndexDiffChangedListenerImplementation implements IndexDiffChangedListener {

		CompletableFuture<IndexDiffData>	isDone	= new CompletableFuture<>();
		Repository							repository;

		@Override
		public void indexDiffChanged(Repository repository, IndexDiffData indexDiffData) {
			IndexDiffCacheEntry indexDiffCacheEntry = cachedEntries.get(this);
			indexDiffCacheEntry.removeIndexDiffChangedListener(this);
			this.repository = repository;
			isDone.complete(indexDiffData);
		}

		public IndexDiffData getDiffs() throws InterruptedException {
			try {
				return isDone.get();
			} catch (java.util.concurrent.ExecutionException e) {
			}
			return null;
		}
	}
}
