package org.opendatamesh.platform.git.provider.gitlab;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opendatamesh.platform.git.exceptions.GitClientException;
import org.opendatamesh.platform.git.exceptions.GitProviderAuthenticationException;
import org.opendatamesh.platform.git.model.*;
import org.opendatamesh.platform.git.provider.GitProviderCredential;
import org.opendatamesh.platform.git.provider.gitlab.credentials.GitLabPatCredential;
import org.opendatamesh.platform.git.provider.gitlab.resources.createmergerequest.GitLabCreateMergeRequestReq;
import org.opendatamesh.platform.git.provider.gitlab.resources.createmergerequest.GitLabCreateMergeRequestRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.getcurrentuser.GitLabGetCurrentUserUserRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.getorganization.GitLabGetOrganizationGroupRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.getrepository.GitLabGetRepositoryProjectRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listbranches.GitLabListBranchesBranchRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listcommits.GitLabCompareCommitsRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listcommits.GitLabListCommitsCommitRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listmembers.GitLabListMembersUserRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listorganizations.GitLabListOrganizationsGroupRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listrepositories.GitLabListRepositoriesProjectRes;
import org.opendatamesh.platform.git.provider.gitlab.resources.listtags.GitLabListTagsTagRes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitLabProviderTest {

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private GitLabProvider gitLabProvider;
    private GitProviderCredential credential;
    private String baseUrl = "https://gitlab.com";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        credential = new GitLabPatCredential("test-token");
        gitLabProvider = new GitLabProvider(baseUrl, restTemplate, credential);
    }

    @Test
    void whenBaseUrlIsNullThenThrowException() {
        assertThatThrownBy(() -> new GitLabProvider(null, restTemplate, credential))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void whenBaseUrlIsBlankThenThrowException() {
        assertThatThrownBy(() -> new GitLabProvider("  ", restTemplate, credential))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void whenGetCurrentUserCalledThenAssertUserReturned() throws Exception {
        // Load JSON response
        GitLabGetCurrentUserUserRes userRes = loadJson("gitlab/get_current_user.json", GitLabGetCurrentUserUserRes.class);
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/user"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabGetCurrentUserUserRes.class)
        )).thenReturn(new ResponseEntity<>(userRes, HttpStatus.OK));

        // Test
        User user = gitLabProvider.getCurrentUser();

        // Verify
        assertThat(user).isNotNull();
        assertThat(user.getUsername()).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/user"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabGetCurrentUserUserRes.class)
        );
    }

    @Test
    void whenListOrganizationsCalledThenAssertOrganizationsReturned() throws Exception {
        // Load JSON response
        GitLabListOrganizationsGroupRes[] groupsRes = loadJson("gitlab/list_organizations.json", GitLabListOrganizationsGroupRes[].class);
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/groups?page={page}&per_page={perPage}&owned={owned}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListOrganizationsGroupRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(groupsRes, HttpStatus.OK));

        // Test
        Page<Organization> organizations = gitLabProvider.listOrganizations(pageable);

        // Verify
        assertThat(organizations).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/groups?page={page}&per_page={perPage}&owned={owned}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListOrganizationsGroupRes[].class),
                anyMap()
        );
    }

    @Test
    void whenGetOrganizationCalledThenAssertOrganizationReturned() throws Exception {
        // Load JSON response
        GitLabGetOrganizationGroupRes groupRes = loadJson("gitlab/get_organization.json", GitLabGetOrganizationGroupRes.class);
        String groupId = "115743511";
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/groups/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabGetOrganizationGroupRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(groupRes, HttpStatus.OK));

        // Test
        Optional<Organization> organization = gitLabProvider.getOrganization(groupId);

        // Verify
        assertThat(organization).isPresent();
        assertThat(organization.get().getName()).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/groups/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabGetOrganizationGroupRes.class),
                anyMap()
        );
    }

    @Test
    void whenListMembersCalledThenAssertMembersReturned() throws Exception {
        // Load JSON response
        GitLabListMembersUserRes[] membersRes = loadJson("gitlab/list_members.json", GitLabListMembersUserRes[].class);
        Organization org = new Organization("115743511", "test-org", "https://gitlab.com/groups/test-org");
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/groups/{groupId}/members?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListMembersUserRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(membersRes, HttpStatus.OK));

        // Test
        Page<User> members = gitLabProvider.listMembers(org, pageable);

        // Verify
        assertThat(members).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/groups/{groupId}/members?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListMembersUserRes[].class),
                anyMap()
        );
    }

    @Test
    void whenListRepositoriesCalledThenAssertRepositoriesReturned() throws Exception {
        // Load JSON response
        GitLabListRepositoriesProjectRes[] projectsRes = loadJson("gitlab/list_repositories.json", GitLabListRepositoriesProjectRes[].class);
        Pageable pageable = PageRequest.of(0, 20);
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        User user = new User("123", "test-user", "Test User", null, null);
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/users/{userId}/projects?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListRepositoriesProjectRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(projectsRes, HttpStatus.OK));

        // Test
        Page<Repository> repositories = gitLabProvider.listRepositories(null, user, parameters, pageable);

        // Verify
        assertThat(repositories).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/users/{userId}/projects?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListRepositoriesProjectRes[].class),
                anyMap()
        );
    }

    @Test
    void whenGetRepositoryCalledThenAssertRepositoryReturned() throws Exception {
        // Load JSON response
        GitLabGetRepositoryProjectRes projectRes = loadJson("gitlab/get_repository.json", GitLabGetRepositoryProjectRes.class);
        String projectId = "75825589";
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/projects/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabGetRepositoryProjectRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(projectRes, HttpStatus.OK));

        // Test
        Optional<Repository> repository = gitLabProvider.getRepository(projectId, null);

        // Verify
        assertThat(repository).isPresent();
        assertThat(repository.get().getName()).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/projects/{id}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabGetRepositoryProjectRes.class),
                anyMap()
        );
    }

    @Test
    void whenListCommitsCalledThenAssertCommitsReturned() throws Exception {
        // Load JSON response
        GitLabListCommitsCommitRes[] commitsRes = loadJson("gitlab/list_commits.json", GitLabListCommitsCommitRes[].class);
        Repository repository = new Repository();
        repository.setId("75825589");
        repository.setName("test-repo");
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/repository/commits?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListCommitsCommitRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(commitsRes, HttpStatus.OK));

        // Test
        Page<Commit> commits = gitLabProvider.listCommits(repository, CommitListNoFilter.getInstance(), pageable);

        // Verify
        assertThat(commits).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/repository/commits?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListCommitsCommitRes[].class),
                anyMap()
        );
    }

    @Test
    void whenListBranchesCalledThenAssertBranchesReturned() throws Exception {
        // Load JSON response
        GitLabListBranchesBranchRes[] branchesRes = loadJson("gitlab/list_branches.json", GitLabListBranchesBranchRes[].class);
        Repository repository = new Repository();
        repository.setId("75825589");
        repository.setName("test-repo");
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/repository/branches?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListBranchesBranchRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(branchesRes, HttpStatus.OK));

        // Test
        Page<Branch> branches = gitLabProvider.listBranches(repository, pageable);

        // Verify
        assertThat(branches).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/repository/branches?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListBranchesBranchRes[].class),
                anyMap()
        );
    }

    @Test
    void whenListTagsCalledThenAssertTagsReturned() throws Exception {
        // Load JSON response
        GitLabListTagsTagRes[] tagsRes = loadJson("gitlab/list_tags.json", GitLabListTagsTagRes[].class);
        Repository repository = new Repository();
        repository.setId("75825589");
        repository.setName("test-repo");
        Pageable pageable = PageRequest.of(0, 20);
        
        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/repository/tags?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListTagsTagRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(tagsRes, HttpStatus.OK));

        // Test
        Page<Tag> tags = gitLabProvider.listTags(repository, pageable);

        // Verify
        assertThat(tags).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/repository/tags?page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListTagsTagRes[].class),
                anyMap()
        );
    }

    @Test
    void whenListCommitsCalledWithTagFiltersThenAssertCommitsReturned() throws Exception {
        // Given
        GitLabCompareCommitsRes filteredCommitsRes = loadJson("gitlab/list_commits_filtered.json", GitLabCompareCommitsRes.class);
        
        Repository repository = new Repository();
        repository.setId("75825589");
        repository.setName("test-repo");
        Pageable pageable = PageRequest.of(0, 20);

        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/repository/compare?from={from}&to={to}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabCompareCommitsRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(filteredCommitsRes, HttpStatus.OK));

        CommitListFilter filters = new CommitListRangeFilter(new CommitRefTag("v1.0.0"), new CommitRefTag("v2.0.0"));

        // When
        Page<Commit> commits = gitLabProvider.listCommits(repository, filters, pageable);

        List<Commit> expectedCommits = new ArrayList<>();
        expectedCommits.add(new Commit("ddeeaa11bb22cc33dd44ee55ff6677889900aabb", "Add new file", "Eloria Starweaver", "eloria.starweaver@mythicmail.realm", Date.from(Instant.parse("2025-11-28T10:58:41Z"))));
        expectedCommits.add(new Commit("f1a2b3c4d5e6f7890abc1234567890abcdef1234", "Edit README.md", "Eloria Starweaver", "eloria.starweaver@mythicmail.realm", Date.from(Instant.parse("2025-11-26T09:44:06Z"))));

        // Verify
        assertThat(commits).isNotNull();
        assertThat(commits.getContent()).isNotEmpty();
        assertThat(commits.getContent().size()).isEqualTo(commits.getContent().size());
        assertThat(commits.getContent())
                .usingRecursiveComparison()
                .isEqualTo(expectedCommits);

        Map<String, Object> queryParams = Map.of(
                "projectId", "75825589",
                "from", "v1.0.0",
                "to", "v2.0.0"
        );

        // Verify that the list commits endpoint was called (not the regular commits endpoint)
        assertThat(commits).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/repository/compare?from={from}&to={to}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabCompareCommitsRes.class),
                eq(queryParams)
        );
    }

    @Test
    void whenListCommitsFilteredByBranchNameThenAssertCommitsReturned() throws Exception {
        // Given
        GitLabListCommitsCommitRes[] commitsRes = loadJson("gitlab/list_commit_by_branch_name/list_commit_by_branch_name.json", GitLabListCommitsCommitRes[].class);
        
        Repository repository = new Repository();
        repository.setId("75825589");
        repository.setName("test-repo");
        Pageable pageable = PageRequest.of(0, 20);

        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/repository/commits?ref_name={branchName}&page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListCommitsCommitRes[].class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(commitsRes, HttpStatus.OK));

        CommitListFilter filters = new CommitListSingleBranchFilter(new CommitRefBranch("test"));

        // When
        Page<Commit> commits = gitLabProvider.listCommits(repository, filters, pageable);

        List<Commit> expectedCommits = new ArrayList<>();
        expectedCommits.add(new Commit("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Edit README.md v2", "User Name", "user@example.com", Date.from(Instant.parse("2026-03-05T11:34:01Z"))));

        // Verify
        assertThat(commits).isNotNull();
        assertThat(commits.getContent()).isNotEmpty();
        assertThat(commits.getContent().size()).isEqualTo(expectedCommits.size());
        assertThat(commits.getContent())
                .usingRecursiveComparison()
                .isEqualTo(expectedCommits);

        Map<String, Object> queryParams = Map.of(
                "projectId", "75825589",
                "branchName", "test",
                "page", 1,
                "perPage", 20
        );

        // Verify
        assertThat(commits).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/repository/commits?ref_name={branchName}&page={page}&per_page={perPage}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(GitLabListCommitsCommitRes[].class),
                eq(queryParams)
        );
    }

    /**
     * Helper method to load JSON from test resources and deserialize it
     */
    private <T> T loadJson(String resourcePath, Class<T> clazz) throws Exception {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IllegalArgumentException("Resource not found: " + resourcePath);
        }
        return objectMapper.readValue(inputStream, clazz);
    }

    /*
     * Scenario Outline: PR-001 Create a Pull Request with an explicit target branch
     *   Given a valid GitLab repository
     *   And a CreatePullRequest with source "update-v2", target "main", title "Update v2", and body "Generated update"
     *   And the source branch has already been pushed
     *   When createPullRequest is called
     *   Then the GitLab create Pull Request endpoint is called once with the expected authenticated POST request
     *   And the provider payload maps source, target, title, and body correctly
     *   And the returned PullRequest contains non-blank id and webUrl
     *   And the returned source branch, target branch, title, body, and state are mapped correctly
     *   And no Git push, merge, or branch deletion is performed
     */
    @Test
    void pr001_createPullRequestWithExplicitTarget() throws Exception {
        GitLabCreateMergeRequestRes mrRes = loadJson("gitlab/create_merge_request.json", GitLabCreateMergeRequestRes.class);
        Repository repository = gitlabRepository();
        CreatePullRequest request = new CreatePullRequest("update-v2", "main", "Update v2", "Generated update");

        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/merge_requests"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(GitLabCreateMergeRequestRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(mrRes, HttpStatus.CREATED));

        PullRequest result = gitLabProvider.createPullRequest(repository, request);

        assertThat(result.getId()).isEqualTo("7");
        assertThat(result.getWebUrl()).contains("merge_requests/7");
        assertThat(result.getSourceBranch()).isEqualTo("update-v2");
        assertThat(result.getTargetBranch()).isEqualTo("main");
        assertThat(result.getTitle()).isEqualTo("Update v2");
        assertThat(result.getBody()).isEqualTo("Generated update");
        assertThat(result.getState()).isEqualTo("opened");

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/merge_requests"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(GitLabCreateMergeRequestRes.class),
                anyMap()
        );
        GitLabCreateMergeRequestReq body = (GitLabCreateMergeRequestReq) entityCaptor.getValue().getBody();
        assertThat(body.getSourceBranch()).isEqualTo("update-v2");
        assertThat(body.getTargetBranch()).isEqualTo("main");
        assertThat(body.getTitle()).isEqualTo("Update v2");
        assertThat(body.getDescription()).isEqualTo("Generated update");
    }

    /*
     * Scenario Outline: PR-002 Default the target branch from the repository
     *   Given a valid GitLab repository whose default branch is "main"
     *   And a CreatePullRequest with source "update-v2", blank target, and title "Update v2"
     *   When createPullRequest is called
     *   Then the provider request uses "main" as the target branch
     *   And the returned PullRequest has target branch "main"
     */
    @Test
    void pr002_defaultTargetBranchFromRepository() throws Exception {
        GitLabCreateMergeRequestRes mrRes = loadJson("gitlab/create_merge_request.json", GitLabCreateMergeRequestRes.class);
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/merge_requests"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(GitLabCreateMergeRequestRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(mrRes, HttpStatus.CREATED));

        PullRequest result = gitLabProvider.createPullRequest(
                gitlabRepository(), new CreatePullRequest("update-v2", null, "Update v2", null));

        assertThat(result.getTargetBranch()).isEqualTo("main");
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/merge_requests"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(GitLabCreateMergeRequestRes.class),
                anyMap()
        );
        assertThat(((GitLabCreateMergeRequestReq) entityCaptor.getValue().getBody()).getTargetBranch()).isEqualTo("main");
    }

    /*
     * Scenario Outline: PR-003 Reject invalid Pull Request input before HTTP
     *   Given a valid GitLab instance
     *   And <invalid input>
     *   When createPullRequest is called
     *   Then an IllegalArgumentException describing the invalid field is thrown
     *   And the provider HTTP API is not called
     */
    @ParameterizedTest
    @CsvSource({
            "nullRepo, update-v2, main, Update v2",
            "blankSource, , main, Update v2",
            "blankTitle, update-v2, main, ",
            "blankDefault, update-v2, , Update v2",
            "sameBranches, main, main, Update v2"
    })
    void pr003_rejectInvalidPullRequestInputBeforeHttp(String caseName, String source, String target, String title) {
        Repository repository = "nullRepo".equals(caseName) ? null : gitlabRepository();
        if (repository != null && "blankDefault".equals(caseName)) {
            repository.setDefaultBranch(null);
        }
        assertThatThrownBy(() -> gitLabProvider.createPullRequest(repository, new CreatePullRequest(source, target, title, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.POST), any(), any(Class.class), anyMap());
    }

    /*
     * Scenario Outline: PR-004 Map provider authentication failure
     *   Given a valid GitLab repository and CreatePullRequest
     *   And the provider create Pull Request API returns HTTP 401
     *   When createPullRequest is called
     *   Then GitProviderAuthenticationException is thrown
     */
    @Test
    void pr004_mapProviderAuthenticationFailure() {
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/merge_requests"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(GitLabCreateMergeRequestRes.class),
                anyMap()
        )).thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> gitLabProvider.createPullRequest(
                gitlabRepository(), new CreatePullRequest("update-v2", "main", "Update v2", null)))
                .isInstanceOf(GitProviderAuthenticationException.class);
    }

    /*
     * Scenario Outline: PR-005 Preserve provider HTTP errors
     *   Given a valid GitLab repository and CreatePullRequest
     *   And the provider create Pull Request API returns a non-401 error with status and response body
     *   When createPullRequest is called
     *   Then GitClientException preserves the provider status and response body
     */
    @Test
    void pr005_preserveProviderHttpErrors() {
        when(restTemplate.exchange(
                eq(baseUrl + "/api/v4/projects/{projectId}/merge_requests"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(GitLabCreateMergeRequestRes.class),
                anyMap()
        )).thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict",
                org.springframework.http.HttpHeaders.EMPTY, "{\"message\":\"exists\"}".getBytes(), null));

        assertThatThrownBy(() -> gitLabProvider.createPullRequest(
                gitlabRepository(), new CreatePullRequest("update-v2", "main", "Update v2", null)))
                .isInstanceOf(GitClientException.class)
                .satisfies(ex -> {
                    GitClientException gitEx = (GitClientException) ex;
                    assertThat(gitEx.getCode()).isEqualTo(409);
                    assertThat(gitEx.getResponseBody()).contains("exists");
                });
    }

    private Repository gitlabRepository() {
        Repository repository = new Repository();
        repository.setId("75825589");
        repository.setName("test-repo");
        repository.setDefaultBranch("main");
        return repository;
    }
}

