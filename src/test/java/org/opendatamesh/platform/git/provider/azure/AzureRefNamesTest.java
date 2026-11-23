package org.opendatamesh.platform.git.provider.azure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AzureRefNamesTest {

    @Test
    void whenFullBranchRefThenReturnShortName() {
        assertThat(AzureRefNames.toShortBranchName("refs/heads/main")).isEqualTo("main");
        assertThat(AzureRefNames.toShortBranchName("refs/heads/feature/x")).isEqualTo("feature/x");
    }

    @Test
    void whenShortNameOrOtherRefThenReturnUnchanged() {
        assertThat(AzureRefNames.toShortBranchName("main")).isEqualTo("main");
        assertThat(AzureRefNames.toShortBranchName("refs/tags/v1")).isEqualTo("refs/tags/v1");
    }

    @Test
    void whenNullThenReturnNull() {
        assertThat(AzureRefNames.toShortBranchName(null)).isNull();
    }
}
