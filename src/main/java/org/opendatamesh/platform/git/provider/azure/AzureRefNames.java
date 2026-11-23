package org.opendatamesh.platform.git.provider.azure;

import org.eclipse.jgit.lib.Constants;

/**
 * Azure DevOps reports branches as full refs ({@code refs/heads/main}); the rest of the library and its
 * callers use short branch names ({@code main}), as the other providers do.
 */
public final class AzureRefNames {

    private AzureRefNames() {
    }

    /**
     * Returns the short branch name for an Azure ref, or the value unchanged when it is not a
     * {@code refs/heads/} ref. {@code null} stays {@code null}: Azure omits the default branch of an empty
     * repository.
     */
    public static String toShortBranchName(String refName) {
        if (refName != null && refName.startsWith(Constants.R_HEADS)) {
            return refName.substring(Constants.R_HEADS.length());
        }
        return refName;
    }
}
