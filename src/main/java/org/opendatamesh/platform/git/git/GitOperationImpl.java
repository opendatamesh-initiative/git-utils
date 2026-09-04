package org.opendatamesh.platform.git.git;

import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.opendatamesh.platform.git.exceptions.GitOperationException;
import org.opendatamesh.platform.git.model.Commit;
import org.opendatamesh.platform.git.model.Repository;
import org.opendatamesh.platform.git.model.RepositoryPointer;
import org.opendatamesh.platform.git.model.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class GitOperationImpl implements GitOperation {

    private final Logger logger = LoggerFactory.getLogger(GitOperationImpl.class);
    private final GitCredential authContext;
    private final JGitFactory gitFactory;

    public GitOperationImpl() {
        this(null, new JGitFactory());
    }

    public GitOperationImpl(GitCredential authContext) {
        this(authContext, new JGitFactory());
    }

    /**
     * Constructor for unit tests: allows injecting a custom factory to mock Git
     * operations.
     */
    protected GitOperationImpl(GitCredential authContext, JGitFactory gitFactory) {
        this.authContext = authContext;
        this.gitFactory = gitFactory;
    }

    @Override
    public void initRepository(Repository repository, Consumer<File> repositoryReader) {
        validateInitRepositoryArgs(repository, repositoryReader);

        File localRepo = null;
        try {
            // Use OS-level secure temporary directories to avoid collisions
            Path tempDir = Files.createTempDirectory("git-init-" + repository.getName() + "-");
            localRepo = tempDir.toFile();

            // Use try-with-resources to automatically close the Git instance
            try (Git git = gitFactory.init().setDirectory(localRepo)
                    .setInitialBranch(repository.getDefaultBranch())
                    .call()) {
                git.remoteAdd()
                        .setName("origin")
                        .setUri(new URIish(repository.getRemoteUrl()))
                        .call();
            }

            repositoryReader.accept(localRepo);

        } catch (GitAPIException | IOException | URISyntaxException e) {
            throw new GitOperationException("initRepository", "Failed to initialize repository: " + e.getMessage(), e);
        } finally {
            if (localRepo != null && localRepo.exists()) {
                deleteRecursively(localRepo);
            }
        }
    }

    @Override
    public void readRepository(Repository repository, RepositoryPointer pointer, Consumer<File> consumer) {
        validateReadRepositoryArgs(repository, pointer, consumer);

        File localRepo = null;
        try {
            Path tempDir = Files.createTempDirectory("git-repo-" + repository.getName() + "-");
            localRepo = tempDir.toFile();

            String cloneUrl = getCloneUrl(repository, authContext.getTransportProtocol());
            CredentialsProvider credentialsProvider = buildCredentialsProvider(authContext);

            // Get all remote references using ls-remote (Very fast, no files downloaded)
            Collection<Ref> remoteRefs = gitFactory.lsRemoteRepository()
                    .setRemote(cloneUrl)
                    .setCredentialsProvider(credentialsProvider)
                    .call();

            boolean isEmptyRepo = remoteRefs.isEmpty();

            CloneCommand cloneCommand = gitFactory.cloneRepository()
                    .setURI(cloneUrl)
                    .setDirectory(localRepo)
                    .setCredentialsProvider(credentialsProvider);

            if (pointer.getRefType() == RepositoryPointer.RefType.COMMIT) {
                cloneRepositoryAndCommitCheckout(pointer, isEmptyRepo, cloneCommand);
            }
            if (pointer.getRefType() == RepositoryPointer.RefType.TAG) {
                cloneRepositoryAndTagCheckout(pointer, remoteRefs, isEmptyRepo, cloneCommand);
            }
            if (pointer.getRefType() == RepositoryPointer.RefType.BRANCH) {
                cloneRepositoryAndBranchCheckout(pointer, remoteRefs, isEmptyRepo, cloneCommand);
            }

            consumer.accept(localRepo);

        } catch (GitAPIException | IOException e) {
            throw new GitOperationException("readRepository", "Failed to clone repository: " + e.getMessage(), e);
        } finally {
            if (localRepo != null && localRepo.exists()) {
                deleteRecursively(localRepo);
            }
        }
    }

    @Override
    public void add(File repoDir, AddMode mode) {
        if (repoDir == null || !repoDir.exists()) {
            throw new GitOperationException("add", "Repository directory must exist and cannot be null");
        }
        if (mode == null) {
            throw new GitOperationException("add", "Add mode is required");
        }

        try (Git git = gitFactory.open(repoDir)) {
            AddCommand add = git.add();
            switch (mode) {
                case ALL:
                    // Matches git add -A for the working tree
                    add.addFilepattern(".");
                    break;
                case TRACKED_ONLY:
                    // Matches git add -u
                    add.setUpdate(true).addFilepattern(".");
                    break;
                case NO_DELETIONS:
                    // Matches git add --ignore-removal .
                    add.addFilepattern(".").setAll(false);
                    break;
            }
            add.call();
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("add", "Failed to stage changes: " + e.getMessage(), e);
        }
    }

    @Override
    public void addFiles(File repoDir, List<File> files) {
        if (repoDir == null || !repoDir.exists()) {
            throw new GitOperationException("addFiles", "Repository directory must exist and cannot be null");
        }
        if (CollectionUtils.isEmpty(files)) {
            return;
        }

        try (Git git = gitFactory.open(repoDir)) {
            AddCommand add = git.add();
            boolean hasValidFiles = false;

            for (File file : files) {
                if (file != null && file.isFile()) {
                    String relativePath = getRelativePath(repoDir, file);
                    add.addFilepattern(relativePath);
                    hasValidFiles = true;
                }
            }

            if (hasValidFiles) {
                add.call();
            }
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("addFiles", "Failed to add files: " + e.getMessage(), e);
        }
    }

    @Override
    public void commit(File repoDir, Commit commit) {
        if (repoDir == null || !repoDir.exists() || commit == null) {
            throw new GitOperationException("commit", "Valid repository directory and commit context are required");
        }

        try (Git git = gitFactory.open(repoDir)) {

            Status status = git.status().call();

            if (status.isClean()) {
                throw new GitOperationException("commit", "No changes to commit. Working tree is clean.");
            }
            CommitCommand commitCmd = git.commit()
                    .setMessage(commit.getMessage());

            if (StringUtils.hasText(commit.getAuthor()) && StringUtils.hasText(commit.getAuthorEmail())) {
                PersonIdent ident = new PersonIdent(commit.getAuthor(), commit.getAuthorEmail());
                commitCmd.setAuthor(ident);
                commitCmd.setCommitter(ident);
            }

            commitCmd.call();
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("commit", "Failed to commit: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isWorkingTreeClean(File repoDir) {
        if (repoDir == null || !repoDir.exists()) {
            throw new GitOperationException("isWorkingTreeClean", "Valid repository directory is required");
        }

        try (Git git = gitFactory.open(repoDir)) {
            return git.status().call().isClean();
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException(
                    "isWorkingTreeClean",
                    "Failed to read working tree status: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public String getCheckedOutCommitSha(File repoDir) {
        if (repoDir == null || !repoDir.exists()) {
            throw new GitOperationException("getCheckedOutCommitSha", "Valid repository directory is required");
        }

        try (Git git = gitFactory.open(repoDir)) {
            ObjectId commitId = git.getRepository().resolve(Constants.HEAD);
            if (commitId == null) {
                throw new GitOperationException(
                        "getCheckedOutCommitSha",
                        "Cannot resolve currently checked-out commit (HEAD)");
            }
            return commitId.getName();
        } catch (IOException e) {
            throw new GitOperationException(
                    "getCheckedOutCommitSha",
                    "Failed to resolve currently checked-out commit: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public void push(File repoDir, boolean pushTags) {
        if (repoDir == null || !repoDir.exists()) {
            throw new GitOperationException("push", "Valid repository directory is required");
        }

        try (Git git = gitFactory.open(repoDir)) {
            CredentialsProvider cp = buildCredentialsProvider(authContext);
            PushCommand pushCommand = git.push()
                    .setRemote(Constants.DEFAULT_REMOTE_NAME)
                    .setCredentialsProvider(cp)
                    .setPushAll();
            if (pushTags) {
                pushCommand.setPushTags();
            }
            pushCommand.call();
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("push", "Failed to push: " + e.getMessage(), e);
        }
    }

    @Override
    public void pushBranch(File repoDir, String branchName) {
        if (repoDir == null || !repoDir.exists()) {
            throw new GitOperationException("pushBranch", "Valid repository directory is required");
        }
        if (!StringUtils.hasText(branchName)) {
            throw new GitOperationException("pushBranch", "Branch name is required");
        }

        String bareName = toBareBranchName(branchName);
        if (!StringUtils.hasText(bareName)) {
            throw new GitOperationException("pushBranch", "Branch name is required");
        }
        String fullBranchRef = Constants.R_HEADS + bareName;

        try (Git git = gitFactory.open(repoDir)) {
            ObjectId localRef = git.getRepository().resolve(fullBranchRef);
            if (localRef == null) {
                throw new GitOperationException("pushBranch", "Local branch does not exist: " + bareName);
            }

            CredentialsProvider cp = buildCredentialsProvider(authContext);
            RefSpec branchRefSpec = new RefSpec(fullBranchRef + ":" + fullBranchRef);
            PushCommand pushCommand = git.push()
                    .setRemote(Constants.DEFAULT_REMOTE_NAME)
                    .setCredentialsProvider(cp)
                    .setRefSpecs(branchRefSpec);
            Iterable<PushResult> pushResults = pushCommand.call();
            assertSuccessfulRemoteUpdates("pushBranch", pushResults);
        } catch (GitOperationException e) {
            throw e;
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("pushBranch", "Failed to push branch: " + e.getMessage(), e);
        }
    }

    @Override
    public void pushTag(File repoDir, String tagName) {
        if (repoDir == null || !repoDir.exists()) {
            throw new GitOperationException("pushTag", "Valid repository directory is required");
        }
        if (!StringUtils.hasText(tagName)) {
            throw new GitOperationException("pushTag", "Tag name is required");
        }

        String bareName = toBareTagName(tagName);
        if (!StringUtils.hasText(bareName)) {
            throw new GitOperationException("pushTag", "Tag name is required");
        }
        String fullTagRef = Constants.R_TAGS + bareName;

        try (Git git = gitFactory.open(repoDir)) {
            ObjectId localRef = git.getRepository().resolve(fullTagRef);
            if (localRef == null) {
                throw new GitOperationException("pushTag", "Local tag does not exist: " + bareName);
            }

            CredentialsProvider cp = buildCredentialsProvider(authContext);
            RefSpec tagRefSpec = new RefSpec(fullTagRef + ":" + fullTagRef);
            PushCommand pushCommand = git.push()
                    .setRemote(Constants.DEFAULT_REMOTE_NAME)
                    .setCredentialsProvider(cp)
                    .setRefSpecs(tagRefSpec);
            Iterable<PushResult> pushResults = pushCommand.call();
            assertSuccessfulRemoteUpdates("pushTag", pushResults);
        } catch (GitOperationException e) {
            throw e;
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("pushTag", "Failed to push tag: " + e.getMessage(), e);
        }
    }

    @Override
    public String createAndCheckoutBranch(File repoDir, String branchName) {
        if (repoDir == null || !repoDir.exists()) {
            throw new GitOperationException("createAndCheckoutBranch", "Valid repository directory is required");
        }
        String bareName = normalizeLocalBranchName("createAndCheckoutBranch", branchName);
        String fullBranchRef = Constants.R_HEADS + bareName;

        try (Git git = gitFactory.open(repoDir)) {
            assertBranchNameAvailableOnLocalAndOrigin(
                    "createAndCheckoutBranch", git.getRepository(), fullBranchRef, bareName);

            git.branchCreate().setName(bareName).call();
            git.checkout().setName(bareName).call();

            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            if (head == null) {
                throw new GitOperationException("createAndCheckoutBranch",
                        "Cannot resolve HEAD after creating branch: " + bareName);
            }
            return head.getName();
        } catch (GitOperationException e) {
            throw e;
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("createAndCheckoutBranch",
                    "Failed to create and check out branch: " + e.getMessage(), e);
        }
    }

    @Override
    public void createAndCheckoutOrphanBranch(File repoDir, String branchName) {
        if (repoDir == null || !repoDir.isDirectory()) {
            throw new GitOperationException("createAndCheckoutOrphanBranch", "Valid repository directory is required");
        }
        String bareName = normalizeLocalBranchName("createAndCheckoutOrphanBranch", branchName);
        String fullBranchRef = Constants.R_HEADS + bareName;

        try (Git git = gitFactory.open(repoDir)) {
            org.eclipse.jgit.lib.Repository repository = git.getRepository();
            assertPristineRepository("createAndCheckoutOrphanBranch", git);
            assertBranchNameAvailableOnLocalAndOrigin(
                    "createAndCheckoutOrphanBranch", repository, fullBranchRef, bareName);

            String originalBranch = repository.getFullBranch();
            ObjectId originalHead = repository.resolve(Constants.HEAD);
            boolean mutationStarted = false;
            try {
                mutationStarted = true;
                git.checkout().setOrphan(true).setName(bareName).call();
                clearIndex(repository);
                deleteWorkTreeContents(repoDir.toPath(), repository.getDirectory().toPath());
                assertEmptyOrphanState(repository, repoDir.toPath(), fullBranchRef);
            } catch (Exception failure) {
                if (mutationStarted) {
                    try {
                        restoreOriginalCheckout(git, originalBranch, originalHead, fullBranchRef);
                    } catch (Exception rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw new GitOperationException("createAndCheckoutOrphanBranch",
                        "Failed to create pure orphan branch: " + failure.getMessage(), failure);
            }
        } catch (GitOperationException e) {
            throw e;
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("createAndCheckoutOrphanBranch",
                    "Failed to open repository: " + e.getMessage(), e);
        }
    }

    @Override
    public String mergeBranch(File repoDir, String sourceBranch, String targetBranch) {
        if (repoDir == null || !repoDir.isDirectory()) {
            throw new GitOperationException("mergeBranch", "Valid repository directory is required");
        }
        String sourceName = normalizeLocalBranchName("mergeBranch", sourceBranch);
        String targetName = normalizeLocalBranchName("mergeBranch", targetBranch);
        if (sourceName.equals(targetName)) {
            throw new GitOperationException("mergeBranch", "Source and target branches must differ");
        }

        String sourceRefName = Constants.R_HEADS + sourceName;
        String targetRefName = Constants.R_HEADS + targetName;
        try (Git git = gitFactory.open(repoDir)) {
            org.eclipse.jgit.lib.Repository repository = git.getRepository();
            assertPristineRepository("mergeBranch", git);
            ObjectId sourceTip = requireLocalBranch(repository, sourceRefName, sourceName);
            ObjectId targetTip = resolveLocalBranchTip(repository, targetRefName);

            if (targetTip == null) {
                return tipPromoteTarget(git, repository, targetRefName, targetName, sourceTip);
            }

            boolean mutationStarted = false;
            try {
                mutationStarted = true;
                git.checkout().setName(targetName).call();
                assertPristineRepository("mergeBranch", git);

                if (sourceTip.equals(targetTip)) {
                    return targetTip.getName();
                }

                String message = "Merge branch '" + sourceName + "' into '" + targetName + "'";
                ObjectId result;
                if (hasCommonAncestor(repository, targetTip, sourceTip)) {
                    MergeResult mergeResult = git.merge()
                            .include(repository.exactRef(sourceRefName))
                            .setMessage(message)
                            .call();
                    if (!mergeResult.getMergeStatus().isSuccessful()) {
                        throw new GitOperationException("mergeBranch",
                                "Merge failed with status " + mergeResult.getMergeStatus());
                    }
                    result = repository.resolve(targetRefName);
                } else {
                    result = mergeUnrelatedHistories(
                            git, repository, targetRefName, targetTip, sourceTip, message);
                }

                if (result == null) {
                    throw new GitOperationException("mergeBranch",
                            "Cannot resolve target branch after merge: " + targetName);
                }
                return result.getName();
            } catch (Exception failure) {
                if (mutationStarted) {
                    try {
                        restoreMergeTarget(git, targetName, targetTip);
                    } catch (Exception rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                if (failure instanceof GitOperationException) {
                    throw (GitOperationException) failure;
                }
                throw new GitOperationException("mergeBranch",
                        "Failed to merge branch: " + failure.getMessage(), failure);
            }
        } catch (GitOperationException e) {
            throw e;
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("mergeBranch",
                    "Failed to open repository: " + e.getMessage(), e);
        }
    }

    @Override
    public String getHeadSha(File repoDir, String branchName) {
        if (repoDir == null || !repoDir.exists()) {
            throw new GitOperationException("getLatestCommitSha", "Valid repository directory is required");
        }
        if (!StringUtils.hasText(branchName)) {
            throw new GitOperationException("getLatestCommitSha",
                    "Branch name is required to retrieve the latest commit SHA");
        }

        try (Git git = gitFactory.open(repoDir)) {
            String branchRef = toFullBranchRef(branchName);
            ObjectId commitId = git.getRepository().resolve(branchRef);
            if (commitId == null) {
                throw new GitOperationException("getLatestCommitSha",
                        "Cannot resolve latest commit for branch: " + branchName);
            }
            return commitId.getName();
        } catch (IOException e) {
            throw new GitOperationException("getLatestCommitSha", "Failed to get latest commit SHA: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public void addTag(File repoDir, Tag tag) {
        if (repoDir == null || !repoDir.exists()) {
            throw new GitOperationException("addTag", "Valid repository directory is required");
        }
        if (tag == null || !StringUtils.hasText(tag.getName()) || !StringUtils.hasText(tag.getCommitHash())) {
            throw new GitOperationException("addTag", "Tag name and target SHA are required");
        }

        try (Git git = gitFactory.open(repoDir)) {
            ObjectId commitId = git.getRepository().resolve(tag.getCommitHash());
            if (commitId == null) {
                throw new GitOperationException("addTag", "Commit not found: " + tag.getCommitHash());
            }

            try (RevWalk revWalk = gitFactory.createRevWalk(git.getRepository())) {
                var revCommit = revWalk.parseCommit(commitId);
                var tagCmd = git.tag().setObjectId(revCommit).setName(tag.getName());

                if (StringUtils.hasText(tag.getMessage())) {
                    tagCmd.setMessage(tag.getMessage());
                }
                if (StringUtils.hasText(tag.getAuthor()) && StringUtils.hasText(tag.getAuthorEmail())) {
                    PersonIdent taggerIdent = new PersonIdent(tag.getAuthor(), tag.getAuthorEmail());
                    tagCmd.setTagger(taggerIdent);
                }
                tagCmd.call();
            }
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("addTag", "Failed to create tag: " + e.getMessage(), e);
        }
    }

    // --- Private Helper & Validation Methods ---

    /**
     * Normalizes a local branch name by removing the prefix and validating the
     * name.
     * 
     * @param operation  The operation that is being performed.
     * @param branchName The branch name to normalize.
     * @return The normalized branch name.
     * @throws GitOperationException If the branch name is invalid.
     */
    private String normalizeLocalBranchName(String operation, String branchName) {
        if (!StringUtils.hasText(branchName)) {
            throw new GitOperationException(operation, "Branch name is required");
        }
        String normalized = branchName.trim();
        if (normalized.startsWith(Constants.R_HEADS)) {
            normalized = normalized.substring(Constants.R_HEADS.length());
        } else if (normalized.startsWith(Constants.R_REFS)) {
            throw new GitOperationException(operation, "Only local branch names are supported");
        }
        if (!StringUtils.hasText(normalized)
                || !org.eclipse.jgit.lib.Repository.isValidRefName(Constants.R_HEADS + normalized)) {
            throw new GitOperationException(operation, "Invalid branch name: " + branchName);
        }
        return normalized;
    }

    /**
     * Asserts that the repository is in a pristine state.
     * 
     * @param operation The operation that is being performed.
     * @param git       The Git instance.
     * @throws GitAPIException If the repository is not in a pristine state.
     */
    private void assertPristineRepository(String operation, Git git) throws GitAPIException {
        org.eclipse.jgit.lib.Repository repository = git.getRepository();
        if (repository.getRepositoryState() != RepositoryState.SAFE) {
            throw new GitOperationException(operation,
                    "Repository has an unfinished operation: " + repository.getRepositoryState());
        }
        Status repositoryStatus = git.status().call();
        if (!repositoryStatus.isClean() || !repositoryStatus.getIgnoredNotInIndex().isEmpty()) {
            throw new GitOperationException(operation,
                    "Repository work tree, index, and ignored files must be pristine");
        }
    }

    /**
     * Asserts that the branch name is available on the local and origin
     * repositories by checking if the branch exists locally, on the origin, or if
     * the full branch reference is already resolved.
     * 
     * @param operation     The operation that is being performed.
     * @param repository    The repository.
     * @param fullBranchRef The full branch reference.
     * @param bareName      The bare branch name.
     * @throws IOException If the branch name is not available on the local and
     *                     origin repositories.
     */
    private void assertBranchNameAvailableOnLocalAndOrigin(
            String operation,
            org.eclipse.jgit.lib.Repository repository,
            String fullBranchRef,
            String bareName) throws IOException {
        if (fullBranchRef.equals(repository.getFullBranch())
                || repository.exactRef(fullBranchRef) != null
                || repository.resolve(fullBranchRef) != null) {
            throw new GitOperationException(operation, "Branch already exists locally: " + bareName);
        }

        String originUrl = repository.getConfig()
                .getString("remote", Constants.DEFAULT_REMOTE_NAME, "url");
        if (!StringUtils.hasText(originUrl)) {
            throw new GitOperationException(operation, "Remote origin URL is not configured");
        }

        try {
            Collection<Ref> remoteRefs = gitFactory.lsRemoteRepository()
                    .setRemote(originUrl)
                    .setCredentialsProvider(buildCredentialsProvider(authContext))
                    .call();
            if (remoteRefs != null && remoteRefs.stream()
                    .anyMatch(ref -> fullBranchRef.equals(ref.getName()))) {
                throw new GitOperationException(operation,
                        "Branch already exists on origin: " + bareName);
            }
        } catch (GitOperationException e) {
            throw e;
        } catch (GitAPIException e) {
            throw new GitOperationException(operation,
                    "Failed to check remote branch existence: " + e.getMessage(), e);
        }
    }

    private void clearIndex(org.eclipse.jgit.lib.Repository repository) throws IOException {
        DirCache index = repository.lockDirCache();
        boolean committed = false;
        try {
            index.clear();
            index.write();
            committed = index.commit();
        } finally {
            if (!committed) {
                index.unlock();
            }
        }
        if (!committed) {
            throw new IOException("Cannot replace the repository index");
        }
    }

    private void deleteWorkTreeContents(Path workTree, Path metadataDirectory) throws IOException {
        Path normalizedMetadata = metadataDirectory.toAbsolutePath().normalize();
        try (Stream<Path> children = Files.list(workTree)) {
            for (Path child : children.toList()) {
                Path normalizedChild = child.toAbsolutePath().normalize();
                if (Constants.DOT_GIT.equals(child.getFileName().toString())
                        || normalizedMetadata.equals(normalizedChild)
                        || normalizedMetadata.startsWith(normalizedChild)) {
                    continue;
                }
                deletePath(child);
            }
        }
    }

    private void deletePath(Path path) throws IOException {
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(item);
            }
        }
    }

    private void assertEmptyOrphanState(
            org.eclipse.jgit.lib.Repository repository,
            Path workTree,
            String fullBranchRef) throws IOException {
        if (!fullBranchRef.equals(repository.getFullBranch())
                || repository.resolve(Constants.HEAD) != null
                || repository.readDirCache().getEntryCount() != 0) {
            throw new IOException("Orphan branch is not unborn with an empty index");
        }
        try (Stream<Path> children = Files.list(workTree)) {
            boolean hasWorkTreeContent = children
                    .anyMatch(path -> !Constants.DOT_GIT.equals(path.getFileName().toString())
                            && !repository.getDirectory().toPath().toAbsolutePath().normalize()
                                    .startsWith(path.toAbsolutePath().normalize()));
            if (hasWorkTreeContent) {
                throw new IOException("Orphan branch work tree is not empty");
            }
        }
    }

    private void restoreOriginalCheckout(
            Git git,
            String originalBranch,
            ObjectId originalHead,
            String orphanRef) throws GitAPIException, IOException {
        if (originalHead != null) {
            String checkoutName = originalBranch != null && originalBranch.startsWith(Constants.R_HEADS)
                    ? originalBranch.substring(Constants.R_HEADS.length())
                    : originalHead.getName();
            git.checkout().setName(checkoutName).setForced(true).call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(originalHead.getName()).call();
        }
        Ref orphan = git.getRepository().exactRef(orphanRef);
        if (orphan != null) {
            git.branchDelete()
                    .setBranchNames(orphanRef.substring(Constants.R_HEADS.length()))
                    .setForce(true)
                    .call();
        }
    }

    private ObjectId requireLocalBranch(
            org.eclipse.jgit.lib.Repository repository,
            String fullRef,
            String bareName) throws IOException {
        ObjectId tip = resolveLocalBranchTip(repository, fullRef);
        if (tip == null) {
            throw new GitOperationException("mergeBranch",
                    "Local branch does not exist or is unborn: " + bareName);
        }
        return tip;
    }

    private ObjectId resolveLocalBranchTip(
            org.eclipse.jgit.lib.Repository repository,
            String fullRef) throws IOException {
        Ref ref = repository.exactRef(fullRef);
        return ref == null ? null : ref.getObjectId();
    }

    /**
     * Points a missing or unborn target branch at the source tip and checks it out.
     * Does not create a merge commit.
     */
    private String tipPromoteTarget(
            Git git,
            org.eclipse.jgit.lib.Repository repository,
            String targetRefName,
            String targetName,
            ObjectId sourceTip) {
        boolean mutationStarted = false;
        try {
            mutationStarted = true;
            RefUpdate update = repository.updateRef(targetRefName);
            update.setNewObjectId(sourceTip);
            update.setForceUpdate(true);
            RefUpdate.Result updateResult = update.update();
            if (updateResult != RefUpdate.Result.NEW
                    && updateResult != RefUpdate.Result.FORCED
                    && updateResult != RefUpdate.Result.FAST_FORWARD
                    && updateResult != RefUpdate.Result.NO_CHANGE) {
                throw new GitOperationException("mergeBranch",
                        "Failed to tip-promote branch '" + targetName + "': " + updateResult);
            }

            git.checkout().setName(targetName).setForced(true).call();
            assertPristineRepository("mergeBranch", git);

            ObjectId promotedTip = repository.resolve(targetRefName);
            if (promotedTip == null || !promotedTip.equals(sourceTip)) {
                throw new GitOperationException("mergeBranch",
                        "Target branch did not tip-promote to source tip: " + targetName);
            }
            return sourceTip.getName();
        } catch (Exception failure) {
            if (mutationStarted) {
                try {
                    rollbackTipPromotion(git, targetRefName, targetName);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (failure instanceof GitOperationException) {
                throw (GitOperationException) failure;
            }
            throw new GitOperationException("mergeBranch",
                    "Failed to tip-promote branch: " + failure.getMessage(), failure);
        }
    }

    private void rollbackTipPromotion(Git git, String targetRefName, String targetName)
            throws GitAPIException, IOException {
        org.eclipse.jgit.lib.Repository repository = git.getRepository();
        Ref target = repository.exactRef(targetRefName);
        if (target != null) {
            git.branchDelete()
                    .setBranchNames(targetName)
                    .setForce(true)
                    .call();
        }
        repository.writeMergeCommitMsg(null);
        repository.writeMergeHeads(null);
    }

    private boolean hasCommonAncestor(
            org.eclipse.jgit.lib.Repository repository,
            ObjectId targetTip,
            ObjectId sourceTip) throws IOException {
        try (RevWalk walk = gitFactory.createRevWalk(repository)) {
            RevCommit target = walk.parseCommit(targetTip);
            RevCommit source = walk.parseCommit(sourceTip);
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(target);
            walk.markStart(source);
            return walk.next() != null;
        }
    }

    private ObjectId mergeUnrelatedHistories(
            Git git,
            org.eclipse.jgit.lib.Repository repository,
            String targetRef,
            ObjectId targetTip,
            ObjectId sourceTip,
            String message) throws IOException, GitAPIException {
        RevTree targetTree;
        RevTree sourceTree;
        try (RevWalk walk = gitFactory.createRevWalk(repository)) {
            targetTree = walk.parseCommit(targetTip).getTree();
            sourceTree = walk.parseCommit(sourceTip).getTree();
        }

        UnrelatedHistoryMerger merger = new UnrelatedHistoryMerger(repository);
        if (!merger.merge(targetTree, sourceTree)) {
            throw new GitOperationException("mergeBranch",
                    "Unrelated histories conflict at " + merger.getUnmergedPaths());
        }

        DirCacheCheckout checkout = new DirCacheCheckout(
                repository, targetTree, repository.lockDirCache(), merger.getResultTreeId());
        checkout.setFailOnConflict(true);
        checkout.checkout();

        repository.writeMergeCommitMsg(message);
        repository.writeMergeHeads(Collections.singletonList(sourceTip));
        ObjectId mergeCommit = git.commit().setMessage(message).call().getId();
        ObjectId updatedTarget = repository.resolve(targetRef);
        if (!mergeCommit.equals(updatedTarget)) {
            throw new GitOperationException("mergeBranch",
                    "Target branch did not advance to the unrelated-history merge commit");
        }
        return mergeCommit;
    }

    private void restoreMergeTarget(Git git, String targetName, ObjectId targetTip)
            throws GitAPIException, IOException {
        String currentBranch = git.getRepository().getBranch();
        if (!targetName.equals(currentBranch)) {
            git.checkout().setName(targetName).setForced(true).call();
        }
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(targetTip.getName()).call();
        git.getRepository().writeMergeCommitMsg(null);
        git.getRepository().writeMergeHeads(null);
    }

    private static final class UnrelatedHistoryMerger extends ResolveMerger {
        private UnrelatedHistoryMerger(org.eclipse.jgit.lib.Repository repository) {
            super(repository, true);
        }

        private boolean merge(RevTree targetTree, RevTree sourceTree) throws IOException {
            return mergeTrees(
                    new org.eclipse.jgit.treewalk.EmptyTreeIterator(),
                    targetTree,
                    sourceTree,
                    false);
        }
    }

    private void cloneRepositoryAndBranchCheckout(RepositoryPointer pointer, Collection<Ref> remoteRefs,
            boolean isEmptyRepo, CloneCommand cloneCommand) throws GitAPIException {
        String refValue = pointer.getRefValue();
        String cleanName = refValue.startsWith(Constants.R_HEADS) ? refValue.substring(Constants.R_HEADS.length())
                : refValue;
        String exactRefPath = Constants.R_HEADS + cleanName;
        boolean refExistsOnRemote = remoteRefs.stream()
                .anyMatch(r -> r.getName().equals(exactRefPath));

        if (!isEmptyRepo && !refExistsOnRemote) {
            throw new GitOperationException("readRepository",
                    String.format("The requested %s '%s' does not exist on the remote repository.",
                            pointer.getRefType().name(), cleanName));
        }

        cloneCommand.setBranch(exactRefPath)
                // Optimization: shallow clone
                .setDepth(1);

        // Optimization: clone of ONLY the requested ref
        if (refExistsOnRemote) {
            cloneCommand.setBranchesToClone(Collections.singletonList(exactRefPath));
        }

        try (Git git = cloneCommand.call()) {
            // If it's a completely empty repo, initialize it with an orphan branch
            if (isEmptyRepo) {
                git.checkout().setOrphan(true).setName(cleanName).call();
            }
            // (If the ref existed, JGit automatically checked it out during the shallow
            // clone)
        }
    }

    private void cloneRepositoryAndTagCheckout(RepositoryPointer pointer, Collection<Ref> remoteRefs,
            boolean isEmptyRepo, CloneCommand cloneCommand) throws GitAPIException {
        String refValue = pointer.getRefValue();

        // Normalize the name and build the exact path
        String cleanName = refValue.startsWith(Constants.R_TAGS) ? refValue.substring(Constants.R_TAGS.length())
                : refValue;
        String exactRefPath = Constants.R_TAGS + cleanName;

        boolean refExistsOnRemote = remoteRefs.stream()
                .anyMatch(r -> r.getName().equals(exactRefPath));

        // Validation
        if (isEmptyRepo) {
            throw new GitOperationException("readRepository",
                    "Cannot checkout a TAG because the remote repository is completely empty.");
        }

        if (!refExistsOnRemote) {
            throw new GitOperationException("readRepository",
                    String.format("The requested %s '%s' does not exist on the remote repository.",
                            pointer.getRefType().name(), cleanName));
        }

        // Optimization: Shallow clone of ONLY the requested ref
        cloneCommand.setBranch(exactRefPath)
                .setBranchesToClone(Collections.singletonList(exactRefPath))
                .setDepth(1);

        cloneCommand.call().close();
    }

    private void cloneRepositoryAndCommitCheckout(RepositoryPointer pointer, boolean isEmptyRepo,
            CloneCommand cloneCommand) throws GitAPIException {
        if (isEmptyRepo) {
            throw new GitOperationException("readRepository",
                    "Cannot checkout a COMMIT because the remote repository is completely empty.");
        }

        // Commits require full history to resolve an arbitrary detached commit hash.
        // We bypass setDepth(1) and setBranch() to do a standard clone.
        try (Git git = cloneCommand.call()) {
            git.checkout().setName(pointer.getRefValue()).call();
        }
    }

    private void validateInitRepositoryArgs(Repository repository, Consumer<File> repositoryReader) {
        if (repository == null || !StringUtils.hasText(repository.getName())
                || !StringUtils.hasText(repository.getRemoteUrl())) {
            throw new GitOperationException("initRepository", "Repository name and remote URL cannot be null or empty");
        }
        if (!StringUtils.hasText(repository.getDefaultBranch())) {
            throw new GitOperationException("initRepository", "Repository default branch cannot be null or empty");
        }
        if (repositoryReader == null) {
            throw new GitOperationException("initRepository", "Repository reader consumer cannot be null");
        }
        if (authContext == null) {
            throw new GitOperationException("initRepository",
                    "GitAuthContext not set. Use constructor with GitAuthContext parameter.");
        }
    }

    private void validateReadRepositoryArgs(Repository repository, RepositoryPointer pointer, Consumer<File> consumer) {
        if (repository == null) {
            throw new GitOperationException("getRepositoryContent", "Repository cannot be null");
        }
        if (pointer == null) {
            throw new GitOperationException("getRepositoryContent", "RepositoryPointer cannot be null");
        }
        if (consumer == null) {
            throw new GitOperationException("getRepositoryContent", "Consumer cannot be null");
        }
        if (authContext == null) {
            throw new GitOperationException("getRepositoryContent",
                    "GitAuthContext not set. Use constructor with GitAuthContext parameter.");
        }
    }

    private String getCloneUrl(Repository repository, GitCredential.TransportProtocol protocol) {
        if (protocol == GitCredential.TransportProtocol.SSH) {
            throw new UnsupportedOperationException("SSH cloning is not supported");
        } else {
            return repository.getCloneUrlHttp();
        }
    }

    private CredentialsProvider buildCredentialsProvider(GitCredential ctx) {
        if (ctx.getTransportProtocol() == GitCredential.TransportProtocol.SSH) {
            return null; // Handled via key-based auth implicitly
        } else {
            return buildCredentialsProvider((GitCredentialHttps) ctx);
        }
    }

    private CredentialsProvider buildCredentialsProvider(GitCredentialHttps ctx) {
        if (ctx.getHttpAuthHeaders() != null) {
            String username = ctx.getHttpAuthHeaders().getFirst("username");
            String password = ctx.getHttpAuthHeaders().getFirst("password");
            if (username != null && password != null) {
                return new UsernamePasswordCredentialsProvider(username, password);
            } else {
                String token = ctx.getHttpAuthHeaders().getFirst("Authorization");
                return new UsernamePasswordCredentialsProvider("dummy", token);
            }
        }
        return null;
    }

    private String getRelativePath(File repoDir, File file) {
        try {
            Path repoPath = repoDir.toPath().toAbsolutePath().normalize();
            Path filePath = file.toPath().toAbsolutePath().normalize();

            if (!filePath.startsWith(repoPath)) {
                throw new GitOperationException("addFiles",
                        "File is not within repository directory: " + file.getPath());
            }

            Path relativePath = repoPath.relativize(filePath);
            return relativePath.toString().replace('\\', '/');
        } catch (Exception e) {
            throw new GitOperationException("addFiles", "Failed to get relative path: " + e.getMessage(), e);
        }
    }

    private void assertSuccessfulRemoteUpdates(String operation, Iterable<PushResult> pushResults) {
        if (pushResults == null) {
            return;
        }
        for (PushResult pushResult : pushResults) {
            for (RemoteRefUpdate update : pushResult.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                    throw new GitOperationException(operation,
                            "Remote rejected update for " + update.getRemoteName()
                                    + ": " + status
                                    + (StringUtils.hasText(update.getMessage()) ? " (" + update.getMessage() + ")"
                                            : ""));
                }
            }
        }
    }

    private String toBareBranchName(String branchName) {
        if (branchName.startsWith(Constants.R_HEADS)) {
            return branchName.substring(Constants.R_HEADS.length());
        }
        return branchName;
    }

    private String toBareTagName(String tagName) {
        if (tagName.startsWith(Constants.R_TAGS)) {
            return tagName.substring(Constants.R_TAGS.length());
        }
        return tagName;
    }

    private String toFullBranchRef(String branchName) {
        if (branchName.startsWith(Constants.R_HEADS) || branchName.startsWith(Constants.R_REMOTES)) {
            return branchName;
        }
        return Constants.R_HEADS + branchName;
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try (Stream<Path> pathStream = Files.walk(file.toPath())) {
            pathStream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(f -> {
                        if (!f.delete()) {
                            logger.warn("Failed to delete temp file/folder: {}", f.getAbsolutePath());
                        }
                    });
        } catch (IOException e) {
            logger.error("Error walking directory for deletion: {}", file.getAbsolutePath(), e);
        }
    }
}