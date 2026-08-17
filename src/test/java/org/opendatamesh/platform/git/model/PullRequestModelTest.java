package org.opendatamesh.platform.git.model;

import org.junit.jupiter.api.Test;
import org.opendatamesh.platform.git.provider.GitProvider;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PullRequestModelTest {

    /*
     * Scenario: DTO-001 CreatePullRequest exposes only create input fields
     *   When the public properties of CreatePullRequest are inspected
     *   Then sourceBranch, targetBranch, title, and body are present
     *   And id, webUrl, and state are absent
     */
    @Test
    void dto001_createPullRequestExposesOnlyCreateInputFields() {
        Set<String> properties = propertyNames(CreatePullRequest.class);

        assertThat(properties).containsExactlyInAnyOrder("sourceBranch", "targetBranch", "title", "body");
        assertThat(properties).doesNotContain("id", "webUrl", "state");
    }

    /*
     * Scenario: DTO-002 PullRequest exposes the normalized create result
     *   When the public properties of PullRequest are inspected
     *   Then id, sourceBranch, targetBranch, title, body, webUrl, and state are present
     *   And GitProvider.createPullRequest accepts CreatePullRequest and returns PullRequest
     */
    @Test
    void dto002_pullRequestExposesNormalizedCreateResult() throws Exception {
        Set<String> properties = propertyNames(PullRequest.class);

        assertThat(properties).containsExactlyInAnyOrder(
                "id", "sourceBranch", "targetBranch", "title", "body", "webUrl", "state");

        Method createPullRequest = GitProvider.class.getMethod(
                "createPullRequest", Repository.class, CreatePullRequest.class);
        assertThat(createPullRequest.getReturnType()).isEqualTo(PullRequest.class);
    }

    private static Set<String> propertyNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(field -> field.getName())
                .collect(Collectors.toSet());
    }
}
