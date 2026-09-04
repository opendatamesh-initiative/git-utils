package org.opendatamesh.platform.git.git;

import org.opendatamesh.platform.git.model.Commit;
import org.opendatamesh.platform.git.model.Repository;
import org.opendatamesh.platform.git.model.RepositoryPointer;
import org.opendatamesh.platform.git.model.Tag;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * Low-level Git operations: init, clone, add, commit, push, tag, branch create,
 * and resolve HEAD SHA.
 * All methods use a local working directory; clone/read operations accept a
 * consumer
 * that receives the repo root. Implementations may throw
 * {@link org.opendatamesh.platform.git.exceptions.GitOperationException}
 * on failure.
 */
public interface GitOperation {

    /**
     * Initializes a new Git repository (bare init, remote "origin", default
     * branch),
     * invokes the consumer with the repo directory, then cleans up the directory.
     *
     * @param repository       repository metadata (name, clone URL, default branch)
     * @param repositoryReader consumer invoked with the local repo root directory
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        init
     *                                                                        or
     *                                                                        remote
     *                                                                        add
     *                                                                        fails
     */
    void initRepository(Repository repository, Consumer<File> repositoryReader);

    /**
     * Clones the repository and checks out the given pointer (branch, tag, or
     * commit),
     * invokes the consumer with the repo directory, then cleans up the directory.
     *
     * @param repository       repository metadata (clone URL, etc.)
     * @param pointer          ref to checkout (branch name, tag name, or commit
     *                         hash)
     * @param repositoryReader consumer invoked with the local repo root directory
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        clone
     *                                                                        or
     *                                                                        checkout
     *                                                                        fails
     */
    void readRepository(Repository repository, RepositoryPointer pointer, Consumer<File> repositoryReader);

    /**
     * Adds one or more files to the Git index (staging area).
     *
     * @param repoDir the local repository root directory
     * @param files   files to add (paths relative to repo; must be files, not
     *                directories)
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        add
     *                                                                        fails
     *                                                                        or
     *                                                                        a
     *                                                                        file
     *                                                                        is
     *                                                                        outside
     *                                                                        the
     *                                                                        repo
     */
    void addFiles(File repoDir, List<File> files);

    /**
     * Stages changes in the repository according to the specified mode.
     *
     * @param repoDir the local repository root directory
     * @param mode    the add mode defining which changes to stage
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException
     *                                                                        if the
     *                                                                        add
     *                                                                        operation
     *                                                                        fails
     */
    void add(File repoDir, AddMode mode);

    /**
     * Stages all changes (new, modified, and deleted). Same as
     * {@link #add(File, AddMode)} with {@link AddMode#ALL}.
     *
     * @param repoDir the local repository root directory
     */
    default void addAll(File repoDir) {
        add(repoDir, AddMode.ALL);
    }

    /**
     * Commits the current index to the repository.
     *
     * @param repoDir the local repository root directory
     * @param commit  commit message and author info
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        commit
     *                                                                        fails
     */
    void commit(File repoDir, Commit commit);

    /**
     * Returns whether the working tree and index are clean (no staged or unstaged
     * changes relative to {@code HEAD}).
     *
     * @param repoDir the local repository root directory
     * @return {@code true} when {@code git status} reports a clean tree
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        the
     *                                                                        repo
     *                                                                        is
     *                                                                        invalid
     *                                                                        or
     *                                                                        status
     *                                                                        cannot
     *                                                                        be
     *                                                                        read
     */
    boolean isWorkingTreeClean(File repoDir);

    /**
     * Returns the full SHA of the commit currently checked out ({@code HEAD}).
     * Works for a branch tip and for detached {@code HEAD} after a tag checkout.
     * Distinct from {@link #getHeadSha(File, String)}, which resolves a named
     * branch ref.
     *
     * @param repoDir the local repository root directory
     * @return the full SHA of the currently checked-out commit
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        the
     *                                                                        repo
     *                                                                        is
     *                                                                        invalid
     *                                                                        or
     *                                                                        {@code HEAD}
     *                                                                        cannot
     *                                                                        be
     *                                                                        resolved
     */
    String getCheckedOutCommitSha(File repoDir);

    /**
     * Creates a local branch from the current {@code HEAD} (including detached
     * HEAD),
     * checks it out, and returns the full tip SHA.
     * <p>
     * Refuses to create the branch when {@code refs/heads/{branchName}} already
     * exists
     * locally or on {@code origin} (remote check via authenticated
     * {@code ls-remote}).
     * Never overwrites or force-updates an existing ref.
     *
     * @param repoDir    the local repository root directory
     * @param branchName bare branch name (a leading {@code refs/heads/} prefix is
     *                   normalized)
     * @return the full SHA of {@code HEAD} after checkout
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        input
     *                                                                        is
     *                                                                        invalid,
     *                                                                        the
     *                                                                        name
     *                                                                        collides
     *                                                                        locally
     *                                                                        or on
     *                                                                        origin,
     *                                                                        remote
     *                                                                        check
     *                                                                        fails,
     *                                                                        or
     *                                                                        JGit
     *                                                                        fails
     */
    String createAndCheckoutBranch(File repoDir, String branchName);

    /**
     * Creates and checks out an unborn orphan branch with an empty index and work
     * tree. The exact branch name must not exist locally or on {@code origin}.
     *
     * @param repoDir    the local repository root directory
     * @param branchName bare branch name (a leading {@code refs/heads/} prefix is normalized)
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if input is invalid,
     *                                                                         the work tree is not pristine,
     *                                                                         the name collides, or cleanup fails
     */
    void createAndCheckoutOrphanBranch(File repoDir, String branchName);

    /**
     * Merges a local source branch into a local target branch, including unrelated
     * orphan history. When the target is missing or unborn, tip-promotes the target
     * to the source tip without creating a merge commit. Leaves the target branch
     * checked out and returns its full tip SHA. Conflicts restore the target to its
     * pre-merge tip when one existed.
     *
     * @param repoDir      the local repository root directory
     * @param sourceBranch source branch name (must resolve to a commit tip)
     * @param targetBranch target branch name (born tip is merged; missing/unborn is tip-promoted)
     * @return full SHA of the target tip after merge or tip promotion
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if validation,
     *                                                                         merge, tip promotion, or rollback fails
     */
    String mergeBranch(File repoDir, String sourceBranch, String targetBranch);

    /**
     * Pushes the current branch (and optionally tags) to the remote.
     *
     * @param repoDir  the local repository root directory
     * @param pushTags whether to push tags as well
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        push
     *                                                                        fails
     *                                                                        or
     *                                                                        credentials
     *                                                                        are
     *                                                                        missing
     */
    void push(File repoDir, boolean pushTags);

    /**
     * Pushes exactly one named local branch to the same branch ref on
     * {@code origin}.
     * Does not force-push and does not push tags or any other branch.
     * Independent of the currently checked-out ref.
     *
     * @param repoDir    the local repository root directory
     * @param branchName bare branch name (a leading {@code refs/heads/} prefix is
     *                   normalized)
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        input
     *                                                                        is
     *                                                                        invalid,
     *                                                                        the
     *                                                                        local
     *                                                                        branch
     *                                                                        does
     *                                                                        not
     *                                                                        exist,
     *                                                                        push
     *                                                                        fails,
     *                                                                        or the
     *                                                                        remote
     *                                                                        rejects
     *                                                                        the
     *                                                                        update
     */
    void pushBranch(File repoDir, String branchName);

    /**
     * Pushes exactly one named local tag to the same tag ref on {@code origin}.
     * Does not force-push and does not push branches or any other tag.
     *
     * @param repoDir the local repository root directory
     * @param tagName bare tag name (a leading {@code refs/tags/} prefix is
     *                normalized)
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        input
     *                                                                        is
     *                                                                        invalid,
     *                                                                        the
     *                                                                        local
     *                                                                        tag
     *                                                                        does
     *                                                                        not
     *                                                                        exist,
     *                                                                        push
     *                                                                        fails,
     *                                                                        or the
     *                                                                        remote
     *                                                                        rejects
     *                                                                        the
     *                                                                        update
     */
    void pushTag(File repoDir, String tagName);

    /**
     * Creates a tag at the given commit (lightweight or annotated depending on tag
     * message).
     *
     * @param repoDir the local repository root directory
     * @param tag     tag name, target commit hash, and optional message
     * @throws GitOperationException if the tag cannot be created
     */
    void addTag(File repoDir, Tag tag);

    /**
     * Returns the SHA of the latest commit (HEAD) for the given branch.
     *
     * @param repoDir    the local repository root directory
     * @param branchName the branch name (e.g. "main", "master")
     * @return the full SHA of the branch HEAD
     * @throws org.opendatamesh.platform.git.exceptions.GitOperationException if
     *                                                                        the
     *                                                                        repo
     *                                                                        is
     *                                                                        invalid
     *                                                                        or
     *                                                                        the
     *                                                                        branch
     *                                                                        cannot
     *                                                                        be
     *                                                                        resolved
     */
    String getHeadSha(File repoDir, String branchName);
}
