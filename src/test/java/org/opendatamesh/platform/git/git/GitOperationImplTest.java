package org.opendatamesh.platform.git.git;

import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opendatamesh.platform.git.exceptions.GitOperationException;
import org.opendatamesh.platform.git.model.Commit;
import org.opendatamesh.platform.git.model.RepositoryPointerBranch;
import org.opendatamesh.platform.git.model.RepositoryPointerCommit;
import org.opendatamesh.platform.git.model.RepositoryPointerTag;
import org.springframework.http.HttpHeaders;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitOperationImplTest {

    private static final String REPO_NAME = "test-repo";
    private static final String REMOTE_URL = "https://example.com/repo.git";
    private static final String DEFAULT_BRANCH = "main";
    private static final String CLONE_URL_HTTP = "https://example.com/repo.git";

    @Mock
    private JGitFactory gitFactory;

    @Mock
    private InitCommand initCommand;

    @Mock
    private Git git;

    @Mock
    private AddCommand addCommand;

    @Mock
    private StatusCommand statusCommand;

    @Mock
    private Status status;

    @Mock
    private CommitCommand commitCommand;

    @Mock
    private PushCommand pushCommand;

    @Mock
    private LsRemoteCommand lsRemoteCommand;

    @Mock
    private CloneCommand cloneCommand;

    @Mock
    private RevWalk revWalk;

    @Mock
    private org.eclipse.jgit.lib.Repository jgitRepository;

    @Mock
    private TagCommand tagCommand;

    @Mock
    private CheckoutCommand checkoutCommand;

    @Mock
    private CreateBranchCommand createBranchCommand;

    @Mock
    private StoredConfig storedConfig;

    private GitCredentialHttps credential;
    private GitOperationImpl sut;

    @BeforeEach
    void setUp() {
        credential = new GitCredentialHttps();
        HttpHeaders headers = new HttpHeaders();
        headers.add("username", "user");
        headers.add("password", "token");
        credential.setHttpAuthHeaders(headers);
        sut = new GitOperationImpl(credential, gitFactory);
    }

    // --- initRepository ---

    /**
     * Scenario: init succeeds via factory; consumer is invoked with the temp repo directory before cleanup.
     * Verifies: init + remote add flow and that the reader receives a valid directory.
     */
    @Test
    void whenInitRepositoryThenInvokeReaderWithRepoDir() throws Exception {
        // Given
        org.opendatamesh.platform.git.model.Repository repo = validRepository();
        AtomicReference<File> capturedDir = new AtomicReference<>();
        Consumer<File> reader = dir -> {
            assertThat(dir).exists();
            assertThat(dir).isDirectory();
            capturedDir.set(dir);
        };

        when(gitFactory.init()).thenReturn(initCommand);
        when(initCommand.setDirectory(any(File.class))).thenReturn(initCommand);
        when(initCommand.setInitialBranch(anyString())).thenReturn(initCommand);
        when(initCommand.call()).thenReturn(git);

        RemoteAddCommand remoteAddCommand = mock(RemoteAddCommand.class);
        when(git.remoteAdd()).thenReturn(remoteAddCommand);
        when(remoteAddCommand.setName(anyString())).thenReturn(remoteAddCommand);
        when(remoteAddCommand.setUri(any())).thenReturn(remoteAddCommand);

        // When
        sut.initRepository(repo, reader);

        // Then
        assertThat(capturedDir.get()).isNotNull();
        verify(gitFactory).init();
        verify(initCommand).setDirectory(any(File.class));
        verify(initCommand).setInitialBranch(DEFAULT_BRANCH);
        verify(initCommand).call();
        verify(git).remoteAdd();
    }

    /**
     * Scenario: JGit init fails with GitAPIException.
     * Verifies: exception is wrapped in GitOperationException with initRepository operation context.
     */
    @Test
    void whenInitRepositoryThrowsGitAPIExceptionThenThrowGitOperationException() throws Exception {
        // Given
        org.opendatamesh.platform.git.model.Repository repo = validRepository();
        Consumer<File> reader = f -> {};

        when(gitFactory.init()).thenReturn(initCommand);
        when(initCommand.setDirectory(any(File.class))).thenReturn(initCommand);
        when(initCommand.setInitialBranch(anyString())).thenReturn(initCommand);
        when(initCommand.call()).thenThrow(new RefNotFoundException("init failed"));

        // When & Then
        assertThatThrownBy(() -> sut.initRepository(repo, reader))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("initRepository")
                .hasMessageContaining("Failed to initialize repository");

        verify(gitFactory).init();
    }

    // --- readRepository ---

    /**
     * Scenario: clone by branch; remote has the branch; shallow clone of only that branch.
     * Verifies: ls-remote, clone with branch, depth 1, setBranchesToClone(single branch), and consumer invoked with repo dir.
     */
    @Test
    void whenReadRepositoryBranchThenInvokeConsumerWithRepoDir() throws Exception {
        // Given
        org.opendatamesh.platform.git.model.Repository repo = validRepository();
        repo.setCloneUrlHttp(CLONE_URL_HTTP);
        RepositoryPointerBranch pointer = new RepositoryPointerBranch("main");
        AtomicReference<File> capturedDir = new AtomicReference<>();
        Consumer<File> consumer = dir -> {
            assertThat(dir).exists();
            capturedDir.set(dir);
        };

        Ref ref = mock(Ref.class);
        when(ref.getName()).thenReturn(Constants.R_HEADS + "main");
        Collection<Ref> refs = Collections.singletonList(ref);

        when(gitFactory.lsRemoteRepository()).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.call()).thenReturn(refs);

        when(gitFactory.cloneRepository()).thenReturn(cloneCommand);
        when(cloneCommand.setURI(anyString())).thenReturn(cloneCommand);
        when(cloneCommand.setDirectory(any(File.class))).thenReturn(cloneCommand);
        when(cloneCommand.setCredentialsProvider(any())).thenReturn(cloneCommand);
        when(cloneCommand.setBranch(anyString())).thenReturn(cloneCommand);
        when(cloneCommand.setBranchesToClone(anyList())).thenReturn(cloneCommand);
        when(cloneCommand.setDepth(anyInt())).thenReturn(cloneCommand);
        when(cloneCommand.call()).thenReturn(git);

        // When
        sut.readRepository(repo, pointer, consumer);

        // Then
        assertThat(capturedDir.get()).isNotNull();
        verify(gitFactory).lsRemoteRepository();
        verify(gitFactory).cloneRepository();
        verify(cloneCommand).setBranch(Constants.R_HEADS + "main");
        verify(cloneCommand).setDepth(1);
        verify(cloneCommand).setBranchesToClone(Collections.singletonList(Constants.R_HEADS + "main"));
        verify(cloneCommand).call();
    }

    /**
     * Scenario: clone by branch when remote has no refs (empty repo); shallow clone and orphan branch are used.
     * Verifies: shallow clone (branch + depth 1), orphan checkout; setBranchesToClone is NOT called (ref does not exist on remote).
     */
    @Test
    void whenReadRepositoryBranchOnEmptyRemoteThenDoNotSetBranchesToClone() throws Exception {
        // Given: branch pointer but remote returns no refs (empty repo)
        org.opendatamesh.platform.git.model.Repository repo = validRepository();
        repo.setCloneUrlHttp(CLONE_URL_HTTP);
        RepositoryPointerBranch pointer = new RepositoryPointerBranch("main");
        AtomicReference<File> capturedDir = new AtomicReference<>();
        Consumer<File> consumer = dir -> {
            assertThat(dir).exists();
            capturedDir.set(dir);
        };

        Collection<Ref> refs = Collections.emptyList();

        when(gitFactory.lsRemoteRepository()).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.call()).thenReturn(refs);

        when(gitFactory.cloneRepository()).thenReturn(cloneCommand);
        when(cloneCommand.setURI(anyString())).thenReturn(cloneCommand);
        when(cloneCommand.setDirectory(any(File.class))).thenReturn(cloneCommand);
        when(cloneCommand.setCredentialsProvider(any())).thenReturn(cloneCommand);
        when(cloneCommand.setBranch(anyString())).thenReturn(cloneCommand);
        when(cloneCommand.setDepth(anyInt())).thenReturn(cloneCommand);
        when(cloneCommand.call()).thenReturn(git);
        when(git.checkout()).thenReturn(checkoutCommand);
        when(checkoutCommand.setOrphan(anyBoolean())).thenReturn(checkoutCommand);
        when(checkoutCommand.setName(anyString())).thenReturn(checkoutCommand);

        // When
        sut.readRepository(repo, pointer, consumer);

        // Then: shallow clone is used but setBranchesToClone must not be called (ref does not exist)
        assertThat(capturedDir.get()).isNotNull();
        verify(gitFactory).lsRemoteRepository();
        verify(gitFactory).cloneRepository();
        verify(cloneCommand).setBranch(Constants.R_HEADS + "main");
        verify(cloneCommand).setDepth(1);
        verify(cloneCommand, never()).setBranchesToClone(anyList());
        verify(cloneCommand).call();
    }

    /**
     * Scenario: clone by tag; remote has the tag; shallow clone for that tag.
     * Verifies: ls-remote, clone with tag ref and depth, consumer invoked.
     */
    @Test
    void whenReadRepositoryTagThenInvokeConsumerWithRepoDir() throws Exception {
        // Given
        org.opendatamesh.platform.git.model.Repository repo = validRepository();
        repo.setCloneUrlHttp(CLONE_URL_HTTP);
        RepositoryPointerTag pointer = new RepositoryPointerTag("v1.0");
        AtomicReference<File> capturedDir = new AtomicReference<>();
        Consumer<File> consumer = dir -> {
            assertThat(dir).exists();
            capturedDir.set(dir);
        };

        Ref ref = mock(Ref.class);
        when(ref.getName()).thenReturn(Constants.R_TAGS + "v1.0");
        Collection<Ref> refs = Collections.singletonList(ref);

        when(gitFactory.lsRemoteRepository()).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.call()).thenReturn(refs);

        when(gitFactory.cloneRepository()).thenReturn(cloneCommand);
        when(cloneCommand.setURI(anyString())).thenReturn(cloneCommand);
        when(cloneCommand.setDirectory(any(File.class))).thenReturn(cloneCommand);
        when(cloneCommand.setCredentialsProvider(any())).thenReturn(cloneCommand);
        when(cloneCommand.setBranch(anyString())).thenReturn(cloneCommand);
        when(cloneCommand.setBranchesToClone(anyList())).thenReturn(cloneCommand);
        when(cloneCommand.setDepth(anyInt())).thenReturn(cloneCommand);
        when(cloneCommand.call()).thenReturn(git);

        // When
        sut.readRepository(repo, pointer, consumer);

        // Then
        assertThat(capturedDir.get()).isNotNull();
        verify(gitFactory).lsRemoteRepository();
        verify(gitFactory).cloneRepository();
        verify(cloneCommand).call();
    }

    /**
     * Scenario: clone by commit hash; full clone then checkout to that commit.
     * Verifies: clone without depth, checkout to given SHA, consumer invoked.
     */
    @Test
    void whenReadRepositoryCommitThenInvokeConsumerWithRepoDir() throws Exception {
        // Given
        org.opendatamesh.platform.git.model.Repository repo = validRepository();
        repo.setCloneUrlHttp(CLONE_URL_HTTP);
        RepositoryPointerCommit pointer = new RepositoryPointerCommit("abc123");
        AtomicReference<File> capturedDir = new AtomicReference<>();
        Consumer<File> consumer = dir -> {
            assertThat(dir).exists();
            capturedDir.set(dir);
        };

        Ref ref = mock(Ref.class);
        Collection<Ref> refs = Collections.singletonList(ref);

        when(gitFactory.lsRemoteRepository()).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.call()).thenReturn(refs);

        when(gitFactory.cloneRepository()).thenReturn(cloneCommand);
        when(cloneCommand.setURI(anyString())).thenReturn(cloneCommand);
        when(cloneCommand.setDirectory(any(File.class))).thenReturn(cloneCommand);
        when(cloneCommand.setCredentialsProvider(any())).thenReturn(cloneCommand);
        when(cloneCommand.call()).thenReturn(git);
        when(git.checkout()).thenReturn(checkoutCommand);
        when(checkoutCommand.setName(anyString())).thenReturn(checkoutCommand);

        // When
        sut.readRepository(repo, pointer, consumer);

        // Then
        assertThat(capturedDir.get()).isNotNull();
        verify(gitFactory).lsRemoteRepository();
        verify(gitFactory).cloneRepository();
        verify(cloneCommand).call();
        verify(git).checkout();
        verify(checkoutCommand).setName("abc123");
    }

    /**
     * Scenario: branch pointer references a branch that does not exist on remote.
     * Verifies: GitOperationException with clear message; clone is never executed.
     */
    @Test
    void whenReadRepositoryBranchDoesNotExistThenThrowGitOperationException() throws Exception {
        // Given
        org.opendatamesh.platform.git.model.Repository repo = validRepository();
        repo.setCloneUrlHttp(CLONE_URL_HTTP);
        RepositoryPointerBranch pointer = new RepositoryPointerBranch("missing-branch");
        Consumer<File> consumer = f -> {};

        Ref ref = mock(Ref.class);
        when(ref.getName()).thenReturn(Constants.R_HEADS + "main");
        Collection<Ref> refs = Collections.singletonList(ref);

        when(gitFactory.lsRemoteRepository()).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.call()).thenReturn(refs);

        when(gitFactory.cloneRepository()).thenReturn(cloneCommand);
        when(cloneCommand.setURI(anyString())).thenReturn(cloneCommand);
        when(cloneCommand.setDirectory(any(File.class))).thenReturn(cloneCommand);
        when(cloneCommand.setCredentialsProvider(any())).thenReturn(cloneCommand);

        // When & Then
        assertThatThrownBy(() -> sut.readRepository(repo, pointer, consumer))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("readRepository")
                .hasMessageContaining("does not exist on the remote");

        verify(gitFactory).lsRemoteRepository();
        verify(cloneCommand, never()).call();
    }

    // --- addFiles ---

    /**
     * Scenario: adding a file that lies inside the repo directory.
     * Verifies: repo is opened, add uses correct filepattern, add is executed.
     */
    @Test
    void whenAddFilesThenCallGitAdd(@TempDir Path tempDir) throws Exception {
        // Given
        File repoDir = tempDir.toFile();
        File file = tempDir.resolve("f.txt").toFile();
        if (!file.createNewFile()) {
            throw new IOException("Could not create test file");
        }

        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.add()).thenReturn(addCommand);
        when(addCommand.addFilepattern(anyString())).thenReturn(addCommand);

        // When
        sut.addFiles(repoDir, List.of(file));

        // Then
        verify(gitFactory).open(repoDir);
        verify(git).add();
        verify(addCommand).addFilepattern("f.txt");
        verify(addCommand).call();
    }

    // --- add (AddMode) ---

    @Test
    void whenAddAllModeThenAddFilepatternDot(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.add()).thenReturn(addCommand);
        when(addCommand.addFilepattern(anyString())).thenReturn(addCommand);

        sut.add(repoDir, AddMode.ALL);

        verify(gitFactory).open(repoDir);
        verify(git).add();
        verify(addCommand).addFilepattern(".");
        verify(addCommand).call();
        verify(addCommand, never()).setUpdate(true);
        verify(addCommand, never()).setAll(false);
    }

    @Test
    void whenAddTrackedOnlyModeThenSetUpdateAndFilepatternDot(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.add()).thenReturn(addCommand);
        when(addCommand.setUpdate(true)).thenReturn(addCommand);
        when(addCommand.addFilepattern(anyString())).thenReturn(addCommand);

        sut.add(repoDir, AddMode.TRACKED_ONLY);

        verify(addCommand).setUpdate(true);
        verify(addCommand).addFilepattern(".");
        verify(addCommand).call();
    }

    @Test
    void whenAddNoDeletionsModeThenSetAllFalseAndFilepatternDot(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.add()).thenReturn(addCommand);
        when(addCommand.addFilepattern(anyString())).thenReturn(addCommand);
        when(addCommand.setAll(false)).thenReturn(addCommand);

        sut.add(repoDir, AddMode.NO_DELETIONS);

        verify(addCommand).addFilepattern(".");
        verify(addCommand).setAll(false);
        verify(addCommand).call();
    }

    @Test
    void whenAddAllThenSameAsAddAllMode(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.add()).thenReturn(addCommand);
        when(addCommand.addFilepattern(anyString())).thenReturn(addCommand);

        sut.addAll(repoDir);

        verify(addCommand).addFilepattern(".");
        verify(addCommand).call();
    }

    @Test
    void whenAddThrowsGitAPIExceptionThenWrapInGitOperationException(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.add()).thenReturn(addCommand);
        when(addCommand.addFilepattern(anyString())).thenReturn(addCommand);
        when(addCommand.call()).thenThrow(new RefNotFoundException("add failed"));

        assertThatThrownBy(() -> sut.add(repoDir, AddMode.ALL))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("add")
                .hasMessageContaining("Failed to stage changes");
    }

    /**
     * Uses a real JGit repository: TRACKED_ONLY stages the tracked change but
     * leaves the untracked file unstaged.
     */
    @Test
    void whenAddTrackedOnlyWithRealRepoThenUntrackedRemainsUnstaged(@TempDir Path tempDir) throws Exception {
        File repoRoot = tempDir.toFile();
        try (Git localGit = Git.init().setDirectory(repoRoot).setInitialBranch("main").call()) {
            Path tracked = tempDir.resolve("tracked.txt");
            Files.writeString(tracked, "v1");
            localGit.add().addFilepattern("tracked.txt").call();
            localGit.commit().setMessage("init").call();
            Files.writeString(tracked, "v2");
            Files.writeString(tempDir.resolve("untracked.txt"), "new");
        }

        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        realSut.add(repoRoot, AddMode.TRACKED_ONLY);

        try (Git openedRepo = Git.open(repoRoot)) {
            org.eclipse.jgit.api.Status st = openedRepo.status().call();
            assertThat(st.getModified()).doesNotContain("tracked.txt");
            assertThat(st.getUntracked()).contains("untracked.txt");
        }
    }

    /**
     * Uses a real JGit repository: NO_DELETIONS does not record a deletion of a
     * tracked file in the index.
     */
    @Test
    void whenAddNoDeletionsWithRealRepoThenDeletionNotStaged(@TempDir Path tempDir) throws Exception {
        File repoRoot = tempDir.toFile();
        Path tracked = tempDir.resolve("gone.txt");
        try (Git localGit = Git.init().setDirectory(repoRoot).setInitialBranch("main").call()) {
            Files.writeString(tracked, "content");
            localGit.add().addFilepattern("gone.txt").call();
            localGit.commit().setMessage("init").call();
            Files.delete(tracked);
        }

        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        realSut.add(repoRoot, AddMode.NO_DELETIONS);

        try (Git openedRepo = Git.open(repoRoot)) {
            org.eclipse.jgit.api.Status st = openedRepo.status().call();
            // Deletion is not staged: file is still in the index but missing from the
            // working tree
            assertThat(st.getMissing()).contains("gone.txt");
        }
    }

    // --- commit ---

    /**
     * Scenario: working tree is clean (no changes to commit).
     * Verifies: GitOperationException with "No changes to commit"; commit is never called.
     */
    @Test
    void whenCommitWithCleanWorkingTreeThenThrowGitOperationException(@TempDir Path tempDir) throws Exception {
        // Given
        File repoDir = tempDir.toFile();
        Commit commit = new Commit("msg", "author", "author@example.com");
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.status()).thenReturn(statusCommand);
        when(statusCommand.call()).thenReturn(status);
        when(status.isClean()).thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> sut.commit(repoDir, commit))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("commit")
                .hasMessageContaining("No changes to commit");

        verify(gitFactory).open(repoDir);
        verify(commitCommand, never()).call();
    }

    /**
     * Scenario: working tree has changes; commit with message and author.
     * Verifies: status checked, commit command built with message and author, commit executed.
     */
    @Test
    void whenCommitWithDirtyWorkingTreeThenCallCommit(@TempDir Path tempDir) throws Exception {
        // Given
        File repoDir = tempDir.toFile();
        Commit commit = new Commit("msg", "author", "author@example.com");
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.status()).thenReturn(statusCommand);
        when(statusCommand.call()).thenReturn(status);
        when(status.isClean()).thenReturn(false);
        when(git.commit()).thenReturn(commitCommand);
        when(commitCommand.setMessage(anyString())).thenReturn(commitCommand);
        when(commitCommand.setAuthor(any())).thenReturn(commitCommand);
        when(commitCommand.setCommitter(any())).thenReturn(commitCommand);

        // When
        sut.commit(repoDir, commit);

        // Then
        verify(gitFactory).open(repoDir);
        verify(git).status();
        verify(git).commit();
        verify(commitCommand).setMessage("msg");
        verify(commitCommand).call();
    }

    // --- createAndCheckoutBranch ---

    /*
     * Scenario: BR-001 Create a branch from an attached HEAD
     *   Given a valid local repository whose current branch points to commit "C1"
     *   And branch "update-v2" does not exist locally
     *   And "git ls-remote origin refs/heads/update-v2" returns no matching ref
     *   When createAndCheckoutBranch is called for "update-v2"
     *   Then local branch "update-v2" is created at commit "C1"
     *   And "update-v2" is checked out
     *   And the returned SHA is the full SHA of commit "C1"
     */
    @Test
    void br001_createBranchFromAttachedHead(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        String expectedSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        stubCreateAndCheckoutHappyPath(repoDir, expectedSha, Collections.emptyList());

        String sha = sut.createAndCheckoutBranch(repoDir, "update-v2");

        assertThat(sha).isEqualTo(expectedSha);
        verify(createBranchCommand).setName("update-v2");
        verify(createBranchCommand).call();
        verify(checkoutCommand).setName("update-v2");
        verify(checkoutCommand).call();
        verify(gitFactory).lsRemoteRepository();
    }

    /*
     * Scenario: BR-002 Create a branch from a detached HEAD
     *   Given a valid local repository with detached HEAD at commit "C1"
     *   And branch "update-v2" does not exist locally or on origin
     *   When createAndCheckoutBranch is called for "update-v2"
     *   Then local branch "update-v2" is created at commit "C1"
     *   And "update-v2" is checked out
     *   And the returned SHA is the full SHA of commit "C1"
     */
    @Test
    void br002_createBranchFromDetachedHead(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        String expectedSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        stubCreateAndCheckoutHappyPath(repoDir, expectedSha, Collections.emptyList());

        String sha = sut.createAndCheckoutBranch(repoDir, "update-v2");

        assertThat(sha).isEqualTo(expectedSha);
        verify(createBranchCommand).call();
        verify(checkoutCommand).call();
    }

    /*
     * Scenario: BR-003 Refuse a branch name that exists locally
     *   Given a valid local repository
     *   And local branch "update-v2" already exists
     *   When createAndCheckoutBranch is called for "update-v2"
     *   Then a GitOperationException with operation "createAndCheckoutBranch" is thrown
     *   And git ls-remote is not called
     *   And no branch is created or checked out
     */
    @Test
    void br003_refuseBranchThatExistsLocally(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        ObjectId existing = mock(ObjectId.class);
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(Constants.R_HEADS + "update-v2")).thenReturn(existing);

        assertThatThrownBy(() -> sut.createAndCheckoutBranch(repoDir, "update-v2"))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("createAndCheckoutBranch")
                .hasMessageContaining("already exists locally");

        verify(gitFactory, never()).lsRemoteRepository();
        verify(git, never()).branchCreate();
        verify(git, never()).checkout();
    }

    /*
     * Scenario: BR-004 Refuse a branch name that exists on origin
     *   Given a valid local repository without local branch "update-v2"
     *   And "git ls-remote origin refs/heads/update-v2" returns that exact remote ref
     *   When createAndCheckoutBranch is called for "update-v2"
     *   Then a GitOperationException with operation "createAndCheckoutBranch" is thrown
     *   And no branch is created or checked out
     */
    @Test
    void br004_refuseBranchThatExistsOnOrigin(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        Ref remoteRef = mock(Ref.class);
        when(remoteRef.getName()).thenReturn(Constants.R_HEADS + "update-v2");

        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(Constants.R_HEADS + "update-v2")).thenReturn(null);
        when(jgitRepository.getConfig()).thenReturn(storedConfig);
        when(storedConfig.getString("remote", Constants.DEFAULT_REMOTE_NAME, "url"))
                .thenReturn(REMOTE_URL);
        when(gitFactory.lsRemoteRepository()).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.call()).thenReturn(List.of(remoteRef));

        assertThatThrownBy(() -> sut.createAndCheckoutBranch(repoDir, "update-v2"))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("createAndCheckoutBranch")
                .hasMessageContaining("already exists on origin");

        verify(git, never()).branchCreate();
        verify(git, never()).checkout();
    }

    /*
     * Scenario: BR-005 Surface a remote existence check failure
     *   Given a valid local repository without local branch "update-v2"
     *   And authenticated git ls-remote fails
     *   When createAndCheckoutBranch is called for "update-v2"
     *   Then a GitOperationException with operation "createAndCheckoutBranch" wraps the failure
     *   And no branch is created or checked out
     */
    @Test
    void br005_surfaceRemoteExistenceCheckFailure(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(Constants.R_HEADS + "update-v2")).thenReturn(null);
        when(jgitRepository.getConfig()).thenReturn(storedConfig);
        when(storedConfig.getString("remote", Constants.DEFAULT_REMOTE_NAME, "url"))
                .thenReturn(REMOTE_URL);
        when(gitFactory.lsRemoteRepository()).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.call()).thenThrow(new TransportException("ls-remote failed"));

        assertThatThrownBy(() -> sut.createAndCheckoutBranch(repoDir, "update-v2"))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("createAndCheckoutBranch")
                .hasMessageContaining("Failed to check remote branch existence")
                .hasCauseInstanceOf(TransportException.class);

        verify(git, never()).branchCreate();
        verify(git, never()).checkout();
    }

    /*
     * Scenario Outline: BR-006 Reject invalid branch creation input
     *   Given <repository condition>
     *   And the requested branch name is <branch name>
     *   When createAndCheckoutBranch is called
     *   Then a GitOperationException with operation "createAndCheckoutBranch" is thrown
     *   And no Git mutation is attempted
     *
     *   Examples:
     *     | repository condition                    | branch name |
     *     | the repository directory is null        | "update-v2" |
     *     | the repository directory does not exist | "update-v2" |
     *     | the repository directory is valid       | blank       |
     *     | the repository has no origin URL         | "update-v2" |
     */
    @ParameterizedTest
    @CsvSource({
            "nullDir, update-v2",
            "missingDir, update-v2",
            "validDir, ",
            "noOrigin, update-v2"
    })
    void br006_rejectInvalidBranchCreationInput(String repositoryCondition, String branchName, @TempDir Path tempDir)
            throws Exception {
        File repoDir;
        if ("nullDir".equals(repositoryCondition)) {
            repoDir = null;
        } else if ("missingDir".equals(repositoryCondition)) {
            repoDir = tempDir.resolve("missing").toFile();
        } else if ("noOrigin".equals(repositoryCondition)) {
            repoDir = tempDir.toFile();
            when(gitFactory.open(repoDir)).thenReturn(git);
            when(git.getRepository()).thenReturn(jgitRepository);
            when(jgitRepository.resolve(anyString())).thenReturn(null);
            when(jgitRepository.getConfig()).thenReturn(storedConfig);
            when(storedConfig.getString("remote", Constants.DEFAULT_REMOTE_NAME, "url")).thenReturn(null);
        } else {
            // validDir with blank branch name — fails before opening the repository
            repoDir = tempDir.toFile();
        }

        File finalRepoDir = repoDir;
        assertThatThrownBy(() -> sut.createAndCheckoutBranch(finalRepoDir, branchName))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("createAndCheckoutBranch");

        verify(git, never()).branchCreate();
        verify(gitFactory, never()).lsRemoteRepository();
    }

    // --- push / pushBranch / pushTag ---

    /*
     * Scenario: PUSH-001 Preserve legacy branch push behavior
     *   Given a valid local repository
     *   When push is called with pushTags false
     *   Then the existing push-all-branches behavior is used
     *   And tags are not pushed
     *   And no selective refspec is configured
     */
    @Test
    void push001_preserveLegacyBranchPushBehavior(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.push()).thenReturn(pushCommand);
        when(pushCommand.setRemote(anyString())).thenReturn(pushCommand);
        when(pushCommand.setCredentialsProvider(any())).thenReturn(pushCommand);
        when(pushCommand.setPushAll()).thenReturn(pushCommand);

        sut.push(repoDir, false);

        verify(gitFactory).open(repoDir);
        verify(git).push();
        verify(pushCommand).setRemote(Constants.DEFAULT_REMOTE_NAME);
        verify(pushCommand).setPushAll();
        verify(pushCommand, never()).setRefSpecs(any(RefSpec.class));
        verify(pushCommand, never()).setPushTags();
        verify(pushCommand).call();
    }

    /*
     * Scenario: PUSH-002 Preserve legacy optional tag push behavior
     *   Given a valid local repository
     *   When push is called with pushTags true
     *   Then the existing push-all-branches behavior is used
     *   And the existing push-all-tags behavior is also used
     *   And no selective refspec is configured
     */
    @Test
    void push002_preserveLegacyOptionalTagPushBehavior(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.push()).thenReturn(pushCommand);
        when(pushCommand.setRemote(anyString())).thenReturn(pushCommand);
        when(pushCommand.setCredentialsProvider(any())).thenReturn(pushCommand);
        when(pushCommand.setPushAll()).thenReturn(pushCommand);
        when(pushCommand.setPushTags()).thenReturn(pushCommand);

        sut.push(repoDir, true);

        verify(pushCommand).setPushAll();
        verify(pushCommand).setPushTags();
        verify(pushCommand, never()).setRefSpecs(any(RefSpec.class));
        verify(pushCommand).call();
    }

    /*
     * Scenario: PUSH-003 Selectively publish one named branch
     *   Given a valid local repository containing branches "main" and "update-v2"
     *   And HEAD is not required to point to "update-v2"
     *   When pushBranch is called for "update-v2"
     *   Then exactly one non-force refspec pushes "refs/heads/update-v2" to "refs/heads/update-v2" on origin
     *   And "main" is not pushed
     *   And no tags are pushed
     *   And the matching remote update is successful or up to date
     */
    @Test
    void push003_selectivelyPublishOneNamedBranch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        ObjectId branchId = mock(ObjectId.class);
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(Constants.R_HEADS + "update-v2")).thenReturn(branchId);
        when(git.push()).thenReturn(pushCommand);
        when(pushCommand.setRemote(anyString())).thenReturn(pushCommand);
        when(pushCommand.setCredentialsProvider(any())).thenReturn(pushCommand);
        when(pushCommand.setRefSpecs(any(RefSpec.class))).thenReturn(pushCommand);
        stubSuccessfulRemoteUpdate(RemoteRefUpdate.Status.OK);

        sut.pushBranch(repoDir, "update-v2");

        ArgumentCaptor<RefSpec> refSpecCaptor = ArgumentCaptor.forClass(RefSpec.class);
        verify(pushCommand).setRemote(Constants.DEFAULT_REMOTE_NAME);
        verify(pushCommand).setRefSpecs(refSpecCaptor.capture());
        assertThat(refSpecCaptor.getValue().toString()).isEqualTo("refs/heads/update-v2:refs/heads/update-v2");
        assertThat(refSpecCaptor.getValue().isForceUpdate()).isFalse();
        verify(pushCommand, never()).setPushAll();
        verify(pushCommand, never()).setPushTags();
        verify(pushCommand).call();
    }

    /*
     * Scenario: PUSH-004 Surface a rejected selective branch update
     *   Given a valid local repository containing branch "update-v2"
     *   And origin rejects "refs/heads/update-v2" as non-fast-forward
     *   When pushBranch is called for "update-v2"
     *   Then a GitOperationException with operation "pushBranch" describes the rejected update
     *   And the rejection is not reported as success
     */
    @Test
    void push004_surfaceRejectedSelectiveBranchUpdate(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        ObjectId branchId = mock(ObjectId.class);
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(Constants.R_HEADS + "update-v2")).thenReturn(branchId);
        when(git.push()).thenReturn(pushCommand);
        when(pushCommand.setRemote(anyString())).thenReturn(pushCommand);
        when(pushCommand.setCredentialsProvider(any())).thenReturn(pushCommand);
        when(pushCommand.setRefSpecs(any(RefSpec.class))).thenReturn(pushCommand);

        RemoteRefUpdate update = mock(RemoteRefUpdate.class);
        when(update.getStatus()).thenReturn(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD);
        when(update.getRemoteName()).thenReturn("refs/heads/update-v2");
        when(update.getMessage()).thenReturn("non-fast-forward");
        PushResult pushResult = mock(PushResult.class);
        when(pushResult.getRemoteUpdates()).thenReturn(List.of(update));
        when(pushCommand.call()).thenReturn(List.of(pushResult));

        assertThatThrownBy(() -> sut.pushBranch(repoDir, "update-v2"))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("pushBranch")
                .hasMessageContaining("REJECTED_NONFASTFORWARD");
    }

    /*
     * Scenario: PUSH-005 Publish only a selected branch to a real bare remote
     *   Given a real local repository connected to a real bare origin
     *   And local branches "main" and "update-v2" both have unpushed commits
     *   When pushBranch is called for "update-v2"
     *   Then origin contains "refs/heads/update-v2" at the local "update-v2" tip
     *   And the unpushed "main" commit is not published
     *   And no tag is published
     */
    @Test
    void push005_publishOnlySelectedBranchToRealBareRemote(@TempDir Path tempDir) throws Exception {
        Path barePath = tempDir.resolve("origin.git");
        Path localPath = tempDir.resolve("local");
        Files.createDirectories(localPath);

        Git.init().setDirectory(barePath.toFile()).setBare(true).call().close();

        String updateV2Head;
        String mainHeadBeforeExtraCommit;
        try (Git local = Git.init().setDirectory(localPath.toFile()).setInitialBranch("main").call()) {
            Files.writeString(localPath.resolve("README.md"), "hello");
            local.add().addFilepattern("README.md").call();
            local.commit().setMessage("init").call();
            local.remoteAdd().setName(Constants.DEFAULT_REMOTE_NAME)
                    .setUri(new URIish(barePath.toUri().toString()))
                    .call();
            local.push().setRemote(Constants.DEFAULT_REMOTE_NAME)
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
            mainHeadBeforeExtraCommit = local.getRepository().resolve(Constants.R_HEADS + "main").getName();

            local.branchCreate().setName("update-v2").call();
            local.checkout().setName("update-v2").call();
            Files.writeString(localPath.resolve("change.txt"), "update");
            local.add().addFilepattern("change.txt").call();
            local.commit().setMessage("update").call();
            updateV2Head = local.getRepository().resolve(Constants.R_HEADS + "update-v2").getName();

            local.checkout().setName("main").call();
            Files.writeString(localPath.resolve("main-only.txt"), "main-extra");
            local.add().addFilepattern("main-only.txt").call();
            local.commit().setMessage("main-extra").call();
        }

        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        realSut.pushBranch(localPath.toFile(), "update-v2");

        try (Git bare = Git.open(barePath.toFile())) {
            Ref remoteUpdate = bare.getRepository().exactRef(Constants.R_HEADS + "update-v2");
            assertThat(remoteUpdate).isNotNull();
            assertThat(remoteUpdate.getObjectId().getName()).isEqualTo(updateV2Head);

            Ref remoteMain = bare.getRepository().exactRef(Constants.R_HEADS + "main");
            assertThat(remoteMain).isNotNull();
            assertThat(remoteMain.getObjectId().getName()).isEqualTo(mainHeadBeforeExtraCommit);

            assertThat(bare.getRepository().exactRef(Constants.R_TAGS + "checkpoint-v2")).isNull();
        }
    }

    /*
     * Scenario: PUSH-006 Selectively publish one named tag
     *   Given a valid local repository containing tags "checkpoint-v1" and "checkpoint-v2"
     *   When pushTag is called for "checkpoint-v2"
     *   Then exactly one non-force refspec pushes "refs/tags/checkpoint-v2" to "refs/tags/checkpoint-v2" on origin
     *   And "checkpoint-v1" is not pushed
     *   And no branch is pushed
     *   And the matching remote update is successful or up to date
     */
    @Test
    void push006_selectivelyPublishOneNamedTag(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        ObjectId tagId = mock(ObjectId.class);
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(Constants.R_TAGS + "checkpoint-v2")).thenReturn(tagId);
        when(git.push()).thenReturn(pushCommand);
        when(pushCommand.setRemote(anyString())).thenReturn(pushCommand);
        when(pushCommand.setCredentialsProvider(any())).thenReturn(pushCommand);
        when(pushCommand.setRefSpecs(any(RefSpec.class))).thenReturn(pushCommand);
        stubSuccessfulRemoteUpdate(RemoteRefUpdate.Status.OK);

        sut.pushTag(repoDir, "checkpoint-v2");

        ArgumentCaptor<RefSpec> refSpecCaptor = ArgumentCaptor.forClass(RefSpec.class);
        verify(pushCommand).setRemote(Constants.DEFAULT_REMOTE_NAME);
        verify(pushCommand).setRefSpecs(refSpecCaptor.capture());
        assertThat(refSpecCaptor.getValue().toString()).isEqualTo("refs/tags/checkpoint-v2:refs/tags/checkpoint-v2");
        assertThat(refSpecCaptor.getValue().isForceUpdate()).isFalse();
        verify(pushCommand, never()).setPushAll();
        verify(pushCommand, never()).setPushTags();
        verify(pushCommand).call();
    }

    /*
     * Scenario: PUSH-007 Surface a rejected selective tag update
     *   Given a valid local repository containing tag "checkpoint-v2"
     *   And origin rejects "refs/tags/checkpoint-v2"
     *   When pushTag is called for "checkpoint-v2"
     *   Then a GitOperationException with operation "pushTag" describes the rejected update
     *   And the rejection is not reported as success
     */
    @Test
    void push007_surfaceRejectedSelectiveTagUpdate(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        ObjectId tagId = mock(ObjectId.class);
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(Constants.R_TAGS + "checkpoint-v2")).thenReturn(tagId);
        when(git.push()).thenReturn(pushCommand);
        when(pushCommand.setRemote(anyString())).thenReturn(pushCommand);
        when(pushCommand.setCredentialsProvider(any())).thenReturn(pushCommand);
        when(pushCommand.setRefSpecs(any(RefSpec.class))).thenReturn(pushCommand);

        RemoteRefUpdate update = mock(RemoteRefUpdate.class);
        when(update.getStatus()).thenReturn(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
        when(update.getRemoteName()).thenReturn("refs/tags/checkpoint-v2");
        when(update.getMessage()).thenReturn("rejected");
        PushResult pushResult = mock(PushResult.class);
        when(pushResult.getRemoteUpdates()).thenReturn(List.of(update));
        when(pushCommand.call()).thenReturn(List.of(pushResult));

        assertThatThrownBy(() -> sut.pushTag(repoDir, "checkpoint-v2"))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("pushTag")
                .hasMessageContaining("REJECTED_OTHER_REASON");
    }

    /*
     * Scenario: PUSH-008 Publish only a selected tag to a real bare remote
     *   Given a real local repository connected to a real bare origin
     *   And local tags "checkpoint-v1" and "checkpoint-v2" are not on origin
     *   And a local branch has an unpushed commit
     *   When pushTag is called for "checkpoint-v2"
     *   Then origin contains "refs/tags/checkpoint-v2" at the local tag target
     *   And "refs/tags/checkpoint-v1" is absent from origin
     *   And the unpushed branch commit is not published
     */
    @Test
    void push008_publishOnlySelectedTagToRealBareRemote(@TempDir Path tempDir) throws Exception {
        Path barePath = tempDir.resolve("origin.git");
        Path localPath = tempDir.resolve("local");
        Files.createDirectories(localPath);

        Git.init().setDirectory(barePath.toFile()).setBare(true).call().close();

        String checkpointV2Target;
        String mainHeadOnOrigin;
        try (Git local = Git.init().setDirectory(localPath.toFile()).setInitialBranch("main").call()) {
            Files.writeString(localPath.resolve("README.md"), "hello");
            local.add().addFilepattern("README.md").call();
            RevCommit first = local.commit().setMessage("init").call();
            local.tag().setName("checkpoint-v1").setObjectId(first).call();

            local.remoteAdd().setName(Constants.DEFAULT_REMOTE_NAME)
                    .setUri(new URIish(barePath.toUri().toString()))
                    .call();
            local.push().setRemote(Constants.DEFAULT_REMOTE_NAME)
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
            mainHeadOnOrigin = local.getRepository().resolve(Constants.R_HEADS + "main").getName();

            Files.writeString(localPath.resolve("next.txt"), "next");
            local.add().addFilepattern("next.txt").call();
            RevCommit second = local.commit().setMessage("next").call();
            local.tag().setName("checkpoint-v2").setObjectId(second).call();
            checkpointV2Target = second.getName();
        }

        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        realSut.pushTag(localPath.toFile(), "checkpoint-v2");

        try (Git bare = Git.open(barePath.toFile())) {
            Ref remoteTag = bare.getRepository().exactRef(Constants.R_TAGS + "checkpoint-v2");
            assertThat(remoteTag).isNotNull();
            Ref peeled = bare.getRepository().getRefDatabase().peel(remoteTag);
            ObjectId tagTarget = peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : peeled.getObjectId();
            assertThat(tagTarget.getName()).isEqualTo(checkpointV2Target);
            assertThat(bare.getRepository().exactRef(Constants.R_TAGS + "checkpoint-v1")).isNull();

            Ref remoteMain = bare.getRepository().exactRef(Constants.R_HEADS + "main");
            assertThat(remoteMain).isNotNull();
            assertThat(remoteMain.getObjectId().getName()).isEqualTo(mainHeadOnOrigin);
        }
    }

    /*
     * Scenario Outline: PUSH-009 Reject invalid selective push input
     *   Given <repository condition>
     *   And the requested <ref type> name is <ref name>
     *   When the selective push method is called
     *   Then a GitOperationException with operation <operation> is thrown
     *   And no push command is executed
     *
     *   Examples:
     *     | repository condition                    | ref type | ref name       | operation    |
     *     | the repository directory is null        | branch   | "update-v2"    | "pushBranch" |
     *     | the repository directory does not exist | branch   | "update-v2"    | "pushBranch" |
     *     | the repository directory is valid       | branch   | blank           | "pushBranch" |
     *     | the local branch does not exist          | branch   | "update-v2"    | "pushBranch" |
     *     | the repository directory is null        | tag      | "checkpoint-v2"| "pushTag"    |
     *     | the repository directory does not exist | tag      | "checkpoint-v2"| "pushTag"    |
     *     | the repository directory is valid       | tag      | blank           | "pushTag"    |
     *     | the local tag does not exist             | tag      | "checkpoint-v2"| "pushTag"    |
     */
    @ParameterizedTest
    @CsvSource({
            "nullDir, branch, update-v2, pushBranch",
            "missingDir, branch, update-v2, pushBranch",
            "validDir, branch, , pushBranch",
            "missingRef, branch, update-v2, pushBranch",
            "nullDir, tag, checkpoint-v2, pushTag",
            "missingDir, tag, checkpoint-v2, pushTag",
            "validDir, tag, , pushTag",
            "missingRef, tag, checkpoint-v2, pushTag"
    })
    void push009_rejectInvalidSelectivePushInput(String repositoryCondition, String refType, String refName,
                                                 String operation, @TempDir Path tempDir) throws Exception {
        File repoDir;
        if ("nullDir".equals(repositoryCondition)) {
            repoDir = null;
        } else if ("missingDir".equals(repositoryCondition)) {
            repoDir = tempDir.resolve("missing").toFile();
        } else if ("missingRef".equals(repositoryCondition)) {
            repoDir = tempDir.toFile();
            when(gitFactory.open(repoDir)).thenReturn(git);
            when(git.getRepository()).thenReturn(jgitRepository);
            when(jgitRepository.resolve(anyString())).thenReturn(null);
        } else {
            repoDir = tempDir.toFile();
        }

        File finalRepoDir = repoDir;
        if ("branch".equals(refType)) {
            assertThatThrownBy(() -> sut.pushBranch(finalRepoDir, refName))
                    .isInstanceOf(GitOperationException.class)
                    .hasMessageContaining(operation);
        } else {
            assertThatThrownBy(() -> sut.pushTag(finalRepoDir, refName))
                    .isInstanceOf(GitOperationException.class)
                    .hasMessageContaining(operation);
        }

        verify(git, never()).push();
    }

    // --- pure orphan branches and local merges ---

    /**
     * ORPH-001, ORPH-002: a pure orphan is unborn and contains no inherited
     * index or work-tree entries; its first commit is parentless.
     */
    @Test
    void orph001_createPureOrphanWithParentlessFirstCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repository").toFile();
        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        try (Git local = initializeRepositoryWithOrigin(repoDir, tempDir.resolve("remote.git"))) {
            Files.writeString(repoDir.toPath().resolve("inherited.txt"), "main");
            commitAll(local, "main");

            realSut.createAndCheckoutOrphanBranch(repoDir, "pure-v1");

            assertThat(local.getRepository().getFullBranch()).isEqualTo(Constants.R_HEADS + "pure-v1");
            assertThat(local.getRepository().resolve(Constants.HEAD)).isNull();
            assertThat(local.getRepository().readDirCache().getEntryCount()).isZero();
            try (var children = Files.list(repoDir.toPath())) {
                assertThat(children.map(path -> path.getFileName().toString()))
                        .containsExactly(".git");
            }

            Files.writeString(repoDir.toPath().resolve("generated.txt"), "generated");
            commitAll(local, "first orphan commit");
            try (RevWalk walk = new RevWalk(local.getRepository())) {
                RevCommit first = walk.parseCommit(local.getRepository().resolve(Constants.HEAD));
                assertThat(first.getParentCount()).isZero();
            }
        }
    }

    /**
     * ORPH-003, ORPH-004, ORPH-005: invalid input, collisions, and dirty or
     * ignored content are rejected without changing HEAD.
     */
    @Test
    void orph003_rejectInvalidOrUnsafeCreationWithoutMutation(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repository").toFile();
        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        try (Git local = initializeRepositoryWithOrigin(repoDir, tempDir.resolve("remote.git"))) {
            Files.writeString(repoDir.toPath().resolve("tracked.txt"), "main");
            commitAll(local, "main");
            ObjectId originalHead = local.getRepository().resolve(Constants.HEAD);

            assertThatThrownBy(() -> realSut.createAndCheckoutOrphanBranch(repoDir, "main"))
                    .isInstanceOf(GitOperationException.class)
                    .hasMessageContaining("already exists locally");
            assertThat(local.getRepository().resolve(Constants.HEAD)).isEqualTo(originalHead);

            Files.writeString(repoDir.toPath().resolve("untracked.txt"), "unsafe");
            assertThatThrownBy(() -> realSut.createAndCheckoutOrphanBranch(repoDir, "pure-v1"))
                    .isInstanceOf(GitOperationException.class)
                    .hasMessageContaining("pristine");
            assertThat(local.getRepository().resolve(Constants.HEAD)).isEqualTo(originalHead);
        }
    }

    /**
     * MERGE-001 and MERGE-005: unrelated histories produce a local two-parent
     * merge commit preserving files from both trees.
     */
    @Test
    void merge001_mergeUnrelatedHistoriesWithOrderedParents(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repository").toFile();
        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        try (Git local = initializeRepositoryWithOrigin(repoDir, tempDir.resolve("remote.git"))) {
            Files.writeString(repoDir.toPath().resolve("user.txt"), "user");
            commitAll(local, "main");
            ObjectId mainTip = local.getRepository().resolve(Constants.HEAD);

            realSut.createAndCheckoutOrphanBranch(repoDir, "pure-v1");
            Files.writeString(repoDir.toPath().resolve("generated.txt"), "generated");
            commitAll(local, "pure");
            ObjectId sourceTip = local.getRepository().resolve(Constants.HEAD);
            local.checkout().setName("main").call();

            String result = realSut.mergeBranch(repoDir, "pure-v1", "main");

            assertThat(local.getRepository().getBranch()).isEqualTo("main");
            assertThat(repoDir.toPath().resolve("user.txt")).hasContent("user");
            assertThat(repoDir.toPath().resolve("generated.txt")).hasContent("generated");
            try (RevWalk walk = new RevWalk(local.getRepository())) {
                RevCommit mergeCommit = walk.parseCommit(ObjectId.fromString(result));
                assertThat(mergeCommit.getParentCount()).isEqualTo(2);
                assertThat(mergeCommit.getParent(0).getId()).isEqualTo(mainTip);
                assertThat(mergeCommit.getParent(1).getId()).isEqualTo(sourceTip);
                assertThat(mergeCommit.getFullMessage())
                        .isEqualTo("Merge branch 'pure-v1' into 'main'");
            }
        }
    }

    /**
     * MERGE-002: an unrelated add/add conflict restores the exact target tip and
     * clears all conflict and merge metadata.
     */
    @Test
    void merge002_rollBackConflictingUnrelatedMerge(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repository").toFile();
        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        try (Git local = initializeRepositoryWithOrigin(repoDir, tempDir.resolve("remote.git"))) {
            Files.writeString(repoDir.toPath().resolve("same.txt"), "target");
            commitAll(local, "main");
            ObjectId mainTip = local.getRepository().resolve(Constants.HEAD);

            realSut.createAndCheckoutOrphanBranch(repoDir, "pure-v1");
            Files.writeString(repoDir.toPath().resolve("same.txt"), "source");
            commitAll(local, "pure");
            local.checkout().setName("main").call();

            assertThatThrownBy(() -> realSut.mergeBranch(repoDir, "pure-v1", "main"))
                    .isInstanceOf(GitOperationException.class)
                    .hasMessageContaining("mergeBranch");

            assertThat(local.getRepository().getBranch()).isEqualTo("main");
            assertThat(local.getRepository().resolve(Constants.HEAD)).isEqualTo(mainTip);
            assertThat(local.status().call().isClean()).isTrue();
            assertThat(local.getRepository().getRepositoryState()).isEqualTo(
                    org.eclipse.jgit.lib.RepositoryState.SAFE);
            assertThat(repoDir.toPath().resolve("same.txt")).hasContent("target");
        }
    }

    /**
     * MERGE-003 and MERGE-004: related histories use normal merge semantics and
     * invalid local branch input (missing or unborn source) does not mutate the repository.
     */
    @Test
    void merge003_mergeRelatedHistoryAndRejectMissingBranch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repository").toFile();
        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        try (Git local = initializeRepositoryWithOrigin(repoDir, tempDir.resolve("remote.git"))) {
            Files.writeString(repoDir.toPath().resolve("base.txt"), "base");
            commitAll(local, "base");
            local.branchCreate().setName("feature").call();
            local.checkout().setName("feature").call();
            Files.writeString(repoDir.toPath().resolve("feature.txt"), "feature");
            commitAll(local, "feature");
            ObjectId featureTip = local.getRepository().resolve(Constants.HEAD);
            local.checkout().setName("main").call();

            assertThat(realSut.mergeBranch(repoDir, "feature", "main"))
                    .isEqualTo(featureTip.getName());
            assertThat(repoDir.toPath().resolve("feature.txt")).hasContent("feature");

            ObjectId currentTip = local.getRepository().resolve(Constants.HEAD);
            assertThatThrownBy(() -> realSut.mergeBranch(repoDir, "missing", "main"))
                    .isInstanceOf(GitOperationException.class)
                    .hasMessageContaining("does not exist");
            assertThat(local.getRepository().resolve(Constants.HEAD)).isEqualTo(currentTip);

            realSut.createAndCheckoutOrphanBranch(repoDir, "unborn-source");
            assertThatThrownBy(() -> realSut.mergeBranch(repoDir, "unborn-source", "main"))
                    .isInstanceOf(GitOperationException.class)
                    .hasMessageContaining("unborn");
            assertThat(local.getRepository().resolve(Constants.R_HEADS + "main")).isEqualTo(currentTip);
        }
    }

    /**
     * FLOW-001: orphan creation, first commit, tag creation, and unrelated merge
     * compose without hidden remote side effects.
     *
     * <pre>
     * Scenario: FLOW-001 Tag the pure commit and merge it into a branch with existing files
     *   Given branch "main" contains committed file "user.txt"
     *   And orphan branch "pure-v1" contains only committed file "generated.txt"
     *   When the pure commit is tagged "checkpoint-v1"
     *   And mergeBranch merges "pure-v1" into "main"
     *   Then tag "checkpoint-v1" still points to the pure commit containing only "generated.txt"
     *   And "main" contains both "user.txt" and "generated.txt"
     *   And the orphan branch has not been pushed
     * </pre>
     */
    @Test
    void flow001_composeOrphanTagAndMerge(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repository").toFile();
        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        try (Git local = initializeRepositoryWithOrigin(repoDir, tempDir.resolve("remote.git"))) {
            Files.writeString(repoDir.toPath().resolve("main.txt"), "main");
            commitAll(local, "main");

            realSut.createAndCheckoutOrphanBranch(repoDir, "pure-v1");
            Files.writeString(repoDir.toPath().resolve("pure.txt"), "pure");
            commitAll(local, "pure");
            String pureTip = local.getRepository().resolve(Constants.HEAD).getName();
            realSut.addTag(repoDir,
                    new org.opendatamesh.platform.git.model.Tag("checkpoint-v1", pureTip));
            local.checkout().setName("main").call();

            String mergeTip = realSut.mergeBranch(repoDir, "pure-v1", "main");

            Ref tag = local.getRepository().exactRef(Constants.R_TAGS + "checkpoint-v1");
            Ref peeled = local.getRepository().getRefDatabase().peel(tag);
            ObjectId tagTarget = peeled.getPeeledObjectId() == null
                    ? peeled.getObjectId() : peeled.getPeeledObjectId();
            assertThat(tagTarget.getName()).isEqualTo(pureTip);
            assertThat(local.getRepository().resolve(Constants.R_HEADS + "main").getName())
                    .isEqualTo(mergeTip);
        }
    }

    /**
     * MERGE-006: missing or unborn target is tip-promoted to the source tip with no
     * merge commit.
     *
     * <pre>
     * Scenario: MERGE-006 Tip-promote when the target branch is missing or unborn
     *   Given orphan source branch "pure-v1" at commit "P1" containing "generated.txt"
     *   And target branch "main" has no resolved tip
     *   And the repository work tree is clean
     *   When mergeBranch merges "pure-v1" into "main"
     *   Then "main" is checked out at commit "P1"
     *   And the returned SHA equals the full SHA of "P1"
     *   And "main" contains "generated.txt"
     *   And no merge commit is created
     *   And neither branch is pushed or deleted
     * </pre>
     */
    @Test
    void merge006_tipPromoteMissingOrUnbornTarget(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repository").toFile();
        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        try (Git local = initializeRepositoryWithOrigin(repoDir, tempDir.resolve("remote.git"))) {
            // Repo starts with unborn "main"; orphan + first commit leaves main without a tip.
            realSut.createAndCheckoutOrphanBranch(repoDir, "pure-v1");
            Files.writeString(repoDir.toPath().resolve("generated.txt"), "generated");
            commitAll(local, "pure");
            ObjectId sourceTip = local.getRepository().resolve(Constants.HEAD);

            assertThat(local.getRepository().resolve(Constants.R_HEADS + "main")).isNull();

            String result = realSut.mergeBranch(repoDir, "pure-v1", "main");

            assertThat(result).isEqualTo(sourceTip.getName());
            assertThat(local.getRepository().getBranch()).isEqualTo("main");
            assertThat(local.getRepository().resolve(Constants.HEAD)).isEqualTo(sourceTip);
            assertThat(repoDir.toPath().resolve("generated.txt")).hasContent("generated");
            try (RevWalk walk = new RevWalk(local.getRepository())) {
                RevCommit tip = walk.parseCommit(sourceTip);
                assertThat(tip.getParentCount()).isZero();
            }
            assertThat(local.getRepository().exactRef(Constants.R_HEADS + "pure-v1")).isNotNull();
        }
    }

    /**
     * FLOW-002: empty integration branch composition uses tip promotion so the
     * checkpoint tag and main share the pure commit.
     *
     * <pre>
     * Scenario: FLOW-002 Tag the pure commit and tip-promote into an empty integration branch
     *   Given target branch "main" has no resolved tip
     *   And orphan branch "pure-v1" contains only committed file "generated.txt"
     *   When the pure commit is tagged "checkpoint-v1"
     *   And mergeBranch merges "pure-v1" into "main"
     *   Then tag "checkpoint-v1" still points to the pure commit containing only "generated.txt"
     *   And "main" is checked out at that pure commit
     *   And "main" contains only "generated.txt"
     *   And no merge commit is created
     *   And the orphan branch has not been pushed
     * </pre>
     */
    @Test
    void flow002_composeOrphanTagAndTipPromote(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repository").toFile();
        GitOperationImpl realSut = new GitOperationImpl(credential, new JGitFactory());
        try (Git local = initializeRepositoryWithOrigin(repoDir, tempDir.resolve("remote.git"))) {
            realSut.createAndCheckoutOrphanBranch(repoDir, "pure-v1");
            Files.writeString(repoDir.toPath().resolve("generated.txt"), "generated");
            commitAll(local, "pure");
            String pureTip = local.getRepository().resolve(Constants.HEAD).getName();
            realSut.addTag(repoDir,
                    new org.opendatamesh.platform.git.model.Tag("checkpoint-v1", pureTip));

            assertThat(local.getRepository().resolve(Constants.R_HEADS + "main")).isNull();

            String promotedTip = realSut.mergeBranch(repoDir, "pure-v1", "main");

            assertThat(promotedTip).isEqualTo(pureTip);
            assertThat(local.getRepository().getBranch()).isEqualTo("main");
            assertThat(local.getRepository().resolve(Constants.HEAD).getName()).isEqualTo(pureTip);
            assertThat(repoDir.toPath().resolve("generated.txt")).hasContent("generated");
            try (var children = Files.list(repoDir.toPath())) {
                assertThat(children.map(path -> path.getFileName().toString()))
                        .containsExactlyInAnyOrder(".git", "generated.txt");
            }
            try (RevWalk walk = new RevWalk(local.getRepository())) {
                RevCommit tip = walk.parseCommit(ObjectId.fromString(promotedTip));
                assertThat(tip.getParentCount()).isZero();
            }
            Ref tag = local.getRepository().exactRef(Constants.R_TAGS + "checkpoint-v1");
            Ref peeled = local.getRepository().getRefDatabase().peel(tag);
            ObjectId tagTarget = peeled.getPeeledObjectId() == null
                    ? peeled.getObjectId() : peeled.getPeeledObjectId();
            assertThat(tagTarget.getName()).isEqualTo(pureTip);
        }
    }

    // --- isWorkingTreeClean ---

    /**
     * Scenario: working tree has no changes.
     * Verifies: status is queried and true is returned.
     */
    @Test
    void whenIsWorkingTreeCleanAndStatusCleanThenReturnTrue(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.status()).thenReturn(statusCommand);
        when(statusCommand.call()).thenReturn(status);
        when(status.isClean()).thenReturn(true);

        assertThat(sut.isWorkingTreeClean(repoDir)).isTrue();

        verify(gitFactory).open(repoDir);
        verify(status).isClean();
    }

    /**
     * Scenario: working tree has changes.
     * Verifies: status is queried and false is returned.
     */
    @Test
    void whenIsWorkingTreeCleanAndStatusDirtyThenReturnFalse(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.status()).thenReturn(statusCommand);
        when(statusCommand.call()).thenReturn(status);
        when(status.isClean()).thenReturn(false);

        assertThat(sut.isWorkingTreeClean(repoDir)).isFalse();

        verify(gitFactory).open(repoDir);
        verify(status).isClean();
    }

    // --- getCheckedOutCommitSha ---

    /**
     * Scenario: HEAD resolves after a tag checkout (detached HEAD).
     * Verifies: Constants.HEAD is resolved and the SHA is returned.
     */
    @Test
    void whenGetCheckedOutCommitShaOnDetachedHeadThenReturnSha(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        String expectedSha = "deadbeefcafebabe";
        ObjectId objectId = mock(ObjectId.class);
        when(objectId.getName()).thenReturn(expectedSha);

        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(Constants.HEAD)).thenReturn(objectId);

        String result = sut.getCheckedOutCommitSha(repoDir);

        assertThat(result).isEqualTo(expectedSha);
        verify(gitFactory).open(repoDir);
        verify(jgitRepository).resolve(Constants.HEAD);
    }

    /**
     * Scenario: HEAD cannot be resolved (e.g. unborn repository).
     * Verifies: GitOperationException with message about currently checked-out commit.
     */
    @Test
    void whenGetCheckedOutCommitShaWithUnresolvableHeadThenThrowGitOperationException(@TempDir Path tempDir)
            throws Exception {
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(Constants.HEAD)).thenReturn(null);

        assertThatThrownBy(() -> sut.getCheckedOutCommitSha(repoDir))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("getCheckedOutCommitSha")
                .hasMessageContaining("Cannot resolve currently checked-out commit");

        verify(gitFactory).open(repoDir);
    }

    // --- getHeadSha ---

    /**
     * Scenario: branch exists and resolves to a commit.
     * Verifies: repository opened, branch ref resolved, SHA returned.
     */
    @Test
    void whenGetHeadShaThenReturnSha(@TempDir Path tempDir) throws Exception {
        // Given
        File repoDir = tempDir.toFile();
        String expectedSha = "abc123def456";
        ObjectId objectId = mock(ObjectId.class);
        when(objectId.getName()).thenReturn(expectedSha);

        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(anyString())).thenReturn(objectId);

        // When
        String result = sut.getHeadSha(repoDir, "main");

        // Then
        assertThat(result).isEqualTo(expectedSha);
        verify(gitFactory).open(repoDir);
        verify(jgitRepository).resolve(Constants.R_HEADS + "main");
    }

    /**
     * Scenario: branch name cannot be resolved (e.g. branch does not exist).
     * Verifies: GitOperationException with message about unable to resolve latest commit.
     */
    @Test
    void whenGetHeadShaWithUnresolvableBranchThenThrowGitOperationException(@TempDir Path tempDir) throws Exception {
        // Given
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(anyString())).thenReturn(null);

        // When & Then
        assertThatThrownBy(() -> sut.getHeadSha(repoDir, "main"))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("getLatestCommitSha")
                .hasMessageContaining("Cannot resolve latest commit");

        verify(gitFactory).open(repoDir);
    }

    // --- addTag ---

    /**
     * Scenario: creating a tag at an existing commit.
     * Verifies: repo opened, commit resolved, RevWalk used, tag command built with name and objectId, tag created.
     */
    @Test
    void whenAddTagThenCallTagCommand(@TempDir Path tempDir) throws Exception {
        // Given
        File repoDir = tempDir.toFile();
        org.opendatamesh.platform.git.model.Tag tag = new org.opendatamesh.platform.git.model.Tag("v1.0", "abc123");
        ObjectId objectId = mock(ObjectId.class);
        RevCommit revCommit = mock(RevCommit.class);

        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve("abc123")).thenReturn(objectId);
        when(gitFactory.createRevWalk(jgitRepository)).thenReturn(revWalk);
        when(revWalk.parseCommit(objectId)).thenReturn(revCommit);
        when(git.tag()).thenReturn(tagCommand);
        when(tagCommand.setObjectId(revCommit)).thenReturn(tagCommand);
        when(tagCommand.setName("v1.0")).thenReturn(tagCommand);

        // When
        sut.addTag(repoDir, tag);

        // Then
        verify(gitFactory).open(repoDir);
        verify(jgitRepository).resolve("abc123");
        verify(gitFactory).createRevWalk(jgitRepository);
        verify(revWalk).parseCommit(objectId);
        verify(git).tag();
        verify(tagCommand).setObjectId(revCommit);
        verify(tagCommand).setName("v1.0");
        verify(tagCommand).call();
    }

    /**
     * Scenario: tag targets a commit hash that does not exist in the repo.
     * Verifies: GitOperationException with "Commit not found" and the given hash.
     */
    @Test
    void whenAddTagWithCommitNotFoundThenThrowGitOperationException(@TempDir Path tempDir) throws Exception {
        // Given
        File repoDir = tempDir.toFile();
        org.opendatamesh.platform.git.model.Tag tag = new org.opendatamesh.platform.git.model.Tag("v1", "nonexistent");
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve("nonexistent")).thenReturn(null);

        // When & Then
        assertThatThrownBy(() -> sut.addTag(repoDir, tag))
                .isInstanceOf(GitOperationException.class)
                .hasMessageContaining("addTag")
                .hasMessageContaining("Commit not found");

        verify(gitFactory).open(repoDir);
    }

    // --- Helpers ---

    private void stubCreateAndCheckoutHappyPath(File repoDir, String expectedSha, Collection<Ref> remoteRefs)
            throws Exception {
        ObjectId headId = mock(ObjectId.class);
        when(headId.getName()).thenReturn(expectedSha);

        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.getRepository()).thenReturn(jgitRepository);
        when(jgitRepository.resolve(Constants.R_HEADS + "update-v2")).thenReturn(null);
        when(jgitRepository.getConfig()).thenReturn(storedConfig);
        when(storedConfig.getString("remote", Constants.DEFAULT_REMOTE_NAME, "url")).thenReturn(REMOTE_URL);
        when(gitFactory.lsRemoteRepository()).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);
        when(lsRemoteCommand.call()).thenReturn(remoteRefs);
        when(git.branchCreate()).thenReturn(createBranchCommand);
        when(createBranchCommand.setName("update-v2")).thenReturn(createBranchCommand);
        when(git.checkout()).thenReturn(checkoutCommand);
        when(checkoutCommand.setName("update-v2")).thenReturn(checkoutCommand);
        when(jgitRepository.resolve(Constants.HEAD)).thenReturn(headId);
    }

    private void stubSuccessfulRemoteUpdate(RemoteRefUpdate.Status status) throws Exception {
        RemoteRefUpdate update = mock(RemoteRefUpdate.class);
        when(update.getStatus()).thenReturn(status);
        PushResult pushResult = mock(PushResult.class);
        when(pushResult.getRemoteUpdates()).thenReturn(List.of(update));
        when(pushCommand.call()).thenReturn(List.of(pushResult));
    }

    private Git initializeRepositoryWithOrigin(File repoDir, Path bareRemote) throws Exception {
        try (Git ignored = Git.init().setBare(true).setDirectory(bareRemote.toFile()).call()) {
            // The bare repository only provides a real local origin for ls-remote.
        }
        Git local = Git.init().setInitialBranch("main").setDirectory(repoDir).call();
        StoredConfig config = local.getRepository().getConfig();
        config.setString("user", null, "name", "Test User");
        config.setString("user", null, "email", "test@example.com");
        config.save();
        local.remoteAdd()
                .setName(Constants.DEFAULT_REMOTE_NAME)
                .setUri(new URIish(bareRemote.toUri().toString()))
                .call();
        return local;
    }

    private void commitAll(Git local, String message) throws Exception {
        local.add().addFilepattern(".").call();
        local.commit().setMessage(message).call();
    }

    private static org.opendatamesh.platform.git.model.Repository validRepository() {
        org.opendatamesh.platform.git.model.Repository repo = new org.opendatamesh.platform.git.model.Repository();
        repo.setName(REPO_NAME);
        repo.setRemoteUrl(REMOTE_URL);
        repo.setDefaultBranch(DEFAULT_BRANCH);
        repo.setCloneUrlHttp(CLONE_URL_HTTP);
        return repo;
    }
}
