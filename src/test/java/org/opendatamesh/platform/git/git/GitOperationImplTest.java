package org.opendatamesh.platform.git.git;

import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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

    // --- push ---

    /**
     * Scenario: push without tags.
     * Verifies: push command uses default remote, setPushAll, and no setPushTags.
     */
    @Test
    void whenPushThenCallPushCommand(@TempDir Path tempDir) throws Exception {
        // Given
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.push()).thenReturn(pushCommand);
        when(pushCommand.setRemote(anyString())).thenReturn(pushCommand);
        when(pushCommand.setCredentialsProvider(any())).thenReturn(pushCommand);
        when(pushCommand.setPushAll()).thenReturn(pushCommand);

        // When
        sut.push(repoDir, false);

        // Then
        verify(gitFactory).open(repoDir);
        verify(git).push();
        verify(pushCommand).setRemote(Constants.DEFAULT_REMOTE_NAME);
        verify(pushCommand).setPushAll();
        verify(pushCommand).call();
    }

    /**
     * Scenario: push with tags (pushTags=true).
     * Verifies: setPushTags() is invoked on the push command.
     */
    @Test
    void whenPushWithTagsThenSetPushTags(@TempDir Path tempDir) throws Exception {
        // Given
        File repoDir = tempDir.toFile();
        when(gitFactory.open(repoDir)).thenReturn(git);
        when(git.push()).thenReturn(pushCommand);
        when(pushCommand.setRemote(anyString())).thenReturn(pushCommand);
        when(pushCommand.setCredentialsProvider(any())).thenReturn(pushCommand);
        when(pushCommand.setPushAll()).thenReturn(pushCommand);
        when(pushCommand.setPushTags()).thenReturn(pushCommand);

        // When
        sut.push(repoDir, true);

        // Then
        verify(pushCommand).setPushTags();
        verify(pushCommand).call();
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

    private static org.opendatamesh.platform.git.model.Repository validRepository() {
        org.opendatamesh.platform.git.model.Repository repo = new org.opendatamesh.platform.git.model.Repository();
        repo.setName(REPO_NAME);
        repo.setRemoteUrl(REMOTE_URL);
        repo.setDefaultBranch(DEFAULT_BRANCH);
        repo.setCloneUrlHttp(CLONE_URL_HTTP);
        return repo;
    }
}
