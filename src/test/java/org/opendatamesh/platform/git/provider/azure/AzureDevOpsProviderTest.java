package org.opendatamesh.platform.git.provider.azure;

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
import org.opendatamesh.platform.git.provider.azure.credentials.AzurePatCredential;
import org.opendatamesh.platform.git.provider.azure.resources.createpullrequest.AzureCreatePullRequestReq;
import org.opendatamesh.platform.git.provider.azure.resources.createpullrequest.AzureCreatePullRequestRes;
import org.opendatamesh.platform.git.provider.azure.resources.getcurrentuser.AzureGetCurrentUserUserResponseRes;
import org.opendatamesh.platform.git.provider.azure.resources.getrepository.AzureGetRepositoryProjectListRes;
import org.opendatamesh.platform.git.provider.azure.resources.getrepository.AzureGetRepositoryRepositoryRes;
import org.opendatamesh.platform.git.provider.azure.resources.listbranches.AzureListBranchesBranchListRes;
import org.opendatamesh.platform.git.provider.azure.resources.listcommits.AzureListCommitsCommitListRes;
import org.opendatamesh.platform.git.provider.azure.resources.listrepositories.AzureListRepositoriesProjectListRes;
import org.opendatamesh.platform.git.provider.azure.resources.listrepositories.AzureListRepositoriesRepositoryListRes;
import org.opendatamesh.platform.git.provider.azure.resources.listtags.AzureListTagsTagListRes;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AzureDevOpsProviderTest {

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private AzureDevOpsProvider azureDevOpsProvider;
    private GitProviderCredential credential;
    private String baseUrl = "https://dev.azure.com";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        credential = new AzurePatCredential("test-token");
        azureDevOpsProvider = new AzureDevOpsProvider(baseUrl, restTemplate, credential);
    }

    @Test
    void whenBaseUrlIsNullThenThrowException() {
        assertThatThrownBy(() -> new AzureDevOpsProvider(null, restTemplate, credential))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void whenBaseUrlIsBlankThenThrowException() {
        assertThatThrownBy(() -> new AzureDevOpsProvider("  ", restTemplate, credential))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void whenGetCurrentUserCalledThenAssertUserReturned() throws Exception {
        // Load JSON response
        AzureGetCurrentUserUserResponseRes userRes = loadJson("azure/get_current_user.json", AzureGetCurrentUserUserResponseRes.class);

        // Mock RestTemplate response
        when(restTemplate.exchange(
                contains("/_apis/connectionData"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureGetCurrentUserUserResponseRes.class)
        )).thenReturn(new ResponseEntity<>(userRes, HttpStatus.OK));

        // Test
        User user = azureDevOpsProvider.getCurrentUser();

        // Verify
        assertThat(user).isNotNull();
        assertThat(user.getUsername()).isNotNull();
        verify(restTemplate, times(1)).exchange(
                contains("/_apis/connectionData"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureGetCurrentUserUserResponseRes.class)
        );
    }

    @Test
    void whenListOrganizationsCalledThenAssertOrganizationsReturned() {
        Pageable pageable = PageRequest.of(0, 20);

        // Test
        Page<Organization> organizations = azureDevOpsProvider.listOrganizations(pageable);

        // Verify
        assertThat(organizations).isNotNull();
        assertThat(organizations.getContent()).isNotEmpty();
    }

    @Test
    void whenGetOrganizationCalledThenAssertOrganizationReturned() {
        String orgId = "default-org";

        // Test
        Optional<Organization> organization = azureDevOpsProvider.getOrganization(orgId);

        // Verify
        assertThat(organization).isPresent();
        assertThat(organization.get().getName()).isEqualTo(orgId);
    }

    @Test
    void whenListMembersCalledThenAssertMembersReturned() throws Exception {
        Organization org = new Organization("default-org", "default-org", baseUrl);
        Pageable pageable = PageRequest.of(0, 20);

        // Mock getCurrentUser call
        AzureGetCurrentUserUserResponseRes userRes = loadJson("azure/get_current_user.json", AzureGetCurrentUserUserResponseRes.class);
        when(restTemplate.exchange(
                contains("/_apis/connectionData"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureGetCurrentUserUserResponseRes.class)
        )).thenReturn(new ResponseEntity<>(userRes, HttpStatus.OK));

        // Test
        Page<User> members = azureDevOpsProvider.listMembers(org, pageable);

        // Verify
        assertThat(members).isNotNull();
        assertThat(members.getContent()).isNotEmpty();
    }

    @Test
    void whenListRepositoriesCalledThenAssertRepositoriesReturned() throws Exception {
        // Load JSON responses
        AzureListRepositoriesProjectListRes projectsRes = loadJson("azure/list_projects.json", AzureListRepositoriesProjectListRes.class);
        AzureListRepositoriesRepositoryListRes reposRes = loadJson("azure/list_repositories.json", AzureListRepositoriesRepositoryListRes.class);
        Pageable pageable = PageRequest.of(0, 20);
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();

        // Mock RestTemplate responses
        when(restTemplate.exchange(
                eq(baseUrl + "/_apis/projects?api-version=7.1"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListRepositoriesProjectListRes.class)
        )).thenReturn(new ResponseEntity<>(projectsRes, HttpStatus.OK));

        when(restTemplate.exchange(
                eq(baseUrl + "/{projectName}/_apis/git/repositories?api-version={apiVersion}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListRepositoriesRepositoryListRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(reposRes, HttpStatus.OK));

        // Test
        Page<Repository> repositories = azureDevOpsProvider.listRepositories(null, null, parameters, pageable);

        // Verify
        assertThat(repositories).isNotNull();
        verify(restTemplate, atLeastOnce()).exchange(
                eq(baseUrl + "/_apis/projects?api-version=7.1"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListRepositoriesProjectListRes.class)
        );
        verify(restTemplate, atLeastOnce()).exchange(
                eq(baseUrl + "/{projectName}/_apis/git/repositories?api-version={apiVersion}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListRepositoriesRepositoryListRes.class),
                anyMap()
        );
    }

    @Test
    void whenGetRepositoryCalledThenAssertRepositoryReturned() throws Exception {
        // Load JSON responses
        AzureGetRepositoryProjectListRes projectsRes = loadJson("azure/list_projects.json", AzureGetRepositoryProjectListRes.class);
        AzureGetRepositoryRepositoryRes repoRes = loadJson("azure/get_repository.json", AzureGetRepositoryRepositoryRes.class);
        String repoId = "test-repo-id";

        // Mock RestTemplate responses
        when(restTemplate.exchange(
                eq(baseUrl + "/_apis/projects?api-version=7.1"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureGetRepositoryProjectListRes.class)
        )).thenReturn(new ResponseEntity<>(projectsRes, HttpStatus.OK));

        when(restTemplate.exchange(
                eq(baseUrl + "/{projectName}/_apis/git/repositories/{repoId}?api-version={apiVersion}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureGetRepositoryRepositoryRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(repoRes, HttpStatus.OK));

        // Test
        Optional<Repository> repository = azureDevOpsProvider.getRepository(repoId, null);

        // Verify
        assertThat(repository).isPresent();
        assertThat(repository.get().getName()).isNotNull();
        verify(restTemplate, atLeastOnce()).exchange(
                eq(baseUrl + "/_apis/projects?api-version=7.1"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureGetRepositoryProjectListRes.class)
        );
        verify(restTemplate, atLeastOnce()).exchange(
                eq(baseUrl + "/{projectName}/_apis/git/repositories/{repoId}?api-version={apiVersion}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureGetRepositoryRepositoryRes.class),
                anyMap()
        );
    }

    @Test
    void whenListCommitsCalledThenAssertCommitsReturned() throws Exception {
        // Load JSON response
        AzureListCommitsCommitListRes commitsRes = loadJson("azure/list_commits.json", AzureListCommitsCommitListRes.class);
        Repository repository = new Repository();
        repository.setId("test-repo-id");
        repository.setOwnerId("default-project");
        Pageable pageable = PageRequest.of(0, 20);

        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/commits?api-version={apiVersion}&$top={top}&$skip={skip}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListCommitsCommitListRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(commitsRes, HttpStatus.OK));

        // Test
        Page<Commit> commits = azureDevOpsProvider.listCommits(repository, CommitListNoFilter.getInstance(), pageable);

        // Verify
        assertThat(commits).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/commits?api-version={apiVersion}&$top={top}&$skip={skip}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListCommitsCommitListRes.class),
                anyMap()
        );
    }

    @Test
    void whenListBranchesCalledThenAssertBranchesReturned() throws Exception {
        // Load JSON response
        AzureListBranchesBranchListRes branchesRes = loadJson("azure/list_branches.json", AzureListBranchesBranchListRes.class);
        Repository repository = new Repository();
        repository.setId("test-repo-id");
        repository.setOwnerId("default-project");
        Pageable pageable = PageRequest.of(0, 20);

        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/refs?api-version={apiVersion}&filter={filter}&$top={top}&$skip={skip}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListBranchesBranchListRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(branchesRes, HttpStatus.OK));

        // Test
        Page<Branch> branches = azureDevOpsProvider.listBranches(repository, pageable);

        // Verify
        assertThat(branches).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/refs?api-version={apiVersion}&filter={filter}&$top={top}&$skip={skip}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListBranchesBranchListRes.class),
                anyMap()
        );
    }

    @Test
    void whenListTagsCalledThenAssertTagsReturned() throws Exception {
        // Load JSON response
        AzureListTagsTagListRes tagsRes = loadJson("azure/list_tags.json", AzureListTagsTagListRes.class);
        Repository repository = new Repository();
        repository.setId("test-repo-id");
        repository.setOwnerId("default-project");
        Pageable pageable = PageRequest.of(0, 20);

        // Mock RestTemplate response
        when(restTemplate.exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/refs?api-version={apiVersion}&filter={filter}&$top={top}&$skip={skip}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListTagsTagListRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(tagsRes, HttpStatus.OK));

        // Test
        Page<Tag> tags = azureDevOpsProvider.listTags(repository, pageable);

        // Verify
        assertThat(tags).isNotNull();
        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/refs?api-version={apiVersion}&filter={filter}&$top={top}&$skip={skip}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListTagsTagListRes.class),
                anyMap()
        );
    }

    @Test
    void whenListCommitsCalledWithTagFiltersThenAssertCommitsReturned() throws Exception {
        // Given
        AzureListCommitsCommitListRes commitsRes = loadJson("azure/list_commits_filtered.json", AzureListCommitsCommitListRes.class);
        Repository repository = new Repository();
        repository.setId("test-repo-id");
        repository.setOwnerId("default-project");
        Pageable pageable = PageRequest.of(0, 20);
        CommitListFilter filters = new CommitListRangeFilter(new CommitRefTag("v1.0.0"), new CommitRefTag("v2.0.0"));
        
        // Mock RestTemplate response for batch commits
        when(restTemplate.exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/commits?api-version={apiVersion}&$top={top}&$skip={skip}&itemVersion.version={from}&itemVersion.versionType={fromType}&compareVersion.version={to}&compareVersion.versionType={toType}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListCommitsCommitListRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(commitsRes, HttpStatus.OK));

        // When
        Page<Commit> commits = azureDevOpsProvider.listCommits(repository, filters, pageable);

        List<Commit> expectedCommits = new ArrayList<>();
        expectedCommits.add(new Commit("aaa1bbb2ccc3ddd4eee5fff61111222233334444", "Updated README.md", "Eloria Starweaver", "eloria.starweaver@mythicforge.realm", Date.from(Instant.parse("2025-12-03T15:42:56Z"))));
        expectedCommits.add(new Commit("bbb2ccc3ddd4eee5fff611112222333344445555", "Updated README.md 3", "Eloria Starweaver", "eloria.starweaver@mythicforge.realm", Date.from(Instant.parse("2025-12-03T15:42:49Z"))));
        expectedCommits.add(new Commit("ccc3ddd4eee5fff6111122223333444455556666", "Updated README.md 2", "Eloria Starweaver", "eloria.starweaver@mythicforge.realm", Date.from(Instant.parse("2025-12-03T15:42:37Z"))));

        // Then
        // Verify
        assertThat(commits).isNotNull();
        assertThat(commits.getContent()).isNotEmpty();
        assertThat(commits.getContent().size()).isEqualTo(commits.getContent().size());
        assertThat(commits.getContent())
                .usingRecursiveComparison()
                .isEqualTo(expectedCommits);

        Map<String, Object> queryParams = Map.of(
                "projectId", "default-project",
                "repoId", "test-repo-id",
                "apiVersion", "7.1",
                "from", "v1.0.0",
                "fromType", "tag",
                "to", "v2.0.0",
                "toType", "tag",
                "top", 20,
                "skip", 0
        );

        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/commits?api-version={apiVersion}&$top={top}&$skip={skip}&itemVersion.version={from}&itemVersion.versionType={fromType}&compareVersion.version={to}&compareVersion.versionType={toType}"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListCommitsCommitListRes.class),
                eq(queryParams)
        );
    }

    @Test
    void whenListCommitsFilteredByBranchNameThenAssertCommitsReturned() throws Exception {
        // Given
        AzureListCommitsCommitListRes commitsRes = loadJson("azure/list_commit_by_branch_name/list_commit_by_branch_name.json", AzureListCommitsCommitListRes.class);
        Repository repository = new Repository();
        repository.setId("test-repo-id");
        repository.setOwnerId("default-project");
        Pageable pageable = PageRequest.of(0, 20);
        CommitListFilter filters = new CommitListSingleBranchFilter(new CommitRefBranch("test"));

        // Mock RestTemplate response for batch commits
        when(restTemplate.exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/commits?api-version={apiVersion}&$top={top}&$skip={skip}&$itemVersion.version={branchName}&$itemVersion.versionType=branch"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListCommitsCommitListRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(commitsRes, HttpStatus.OK));

        // When
        Page<Commit> commits = azureDevOpsProvider.listCommits(repository, filters, pageable);

        List<Commit> expectedCommits = new ArrayList<>();
        expectedCommits.add(new Commit("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Updated README.md 2", "John Doe", "user@example.com", Date.from(Instant.parse("2026-03-05T11:40:41Z"))));

        // Then
        // Verify
        assertThat(commits).isNotNull();
        assertThat(commits.getContent()).isNotEmpty();
        assertThat(commits.getContent().size()).isEqualTo(expectedCommits.size());
        assertThat(commits.getContent())
                .usingRecursiveComparison()
                .isEqualTo(expectedCommits);

        Map<String, Object> queryParams = Map.of(
                "projectId", "default-project",
                "repoId", "test-repo-id",
                "apiVersion", "7.1",
                "branchName", "test",
                "top", 20,
                "skip", 0
        );

        verify(restTemplate, times(1)).exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/commits?api-version={apiVersion}&$top={top}&$skip={skip}&$itemVersion.version={branchName}&$itemVersion.versionType=branch"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(AzureListCommitsCommitListRes.class),
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
     *   Given a valid Azure DevOps repository
     *   And a CreatePullRequest with source "update-v2", target "main", title "Update v2", and body "Generated update"
     *   And the source branch has already been pushed
     *   When createPullRequest is called
     *   Then the Azure DevOps create Pull Request endpoint is called once with the expected authenticated POST request
     *   And the provider payload maps source, target, title, and body correctly
     *   And the returned PullRequest contains non-blank id and webUrl
     *   And the returned source branch, target branch, title, body, and state are mapped correctly
     *   And no Git push, merge, or branch deletion is performed
     */
    @Test
    void pr001_createPullRequestWithExplicitTarget() throws Exception {
        AzureCreatePullRequestRes prRes = loadJson("azure/create_pull_request.json", AzureCreatePullRequestRes.class);
        Repository repository = azureRepository();
        CreatePullRequest request = new CreatePullRequest("update-v2", "main", "Update v2", "Generated update");

        when(restTemplate.exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/pullrequests?api-version={apiVersion}"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(AzureCreatePullRequestRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(prRes, HttpStatus.CREATED));

        PullRequest result = azureDevOpsProvider.createPullRequest(repository, request);

        assertThat(result.getId()).isEqualTo("99");
        assertThat(result.getWebUrl()).contains("pullrequest/99");
        assertThat(result.getSourceBranch()).isEqualTo("update-v2");
        assertThat(result.getTargetBranch()).isEqualTo("main");
        assertThat(result.getTitle()).isEqualTo("Update v2");
        assertThat(result.getBody()).isEqualTo("Generated update");
        assertThat(result.getState()).isEqualTo("active");

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/pullrequests?api-version={apiVersion}"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(AzureCreatePullRequestRes.class),
                anyMap()
        );
        AzureCreatePullRequestReq body = (AzureCreatePullRequestReq) entityCaptor.getValue().getBody();
        assertThat(body.getSourceRefName()).isEqualTo("refs/heads/update-v2");
        assertThat(body.getTargetRefName()).isEqualTo("refs/heads/main");
        assertThat(body.getTitle()).isEqualTo("Update v2");
        assertThat(body.getDescription()).isEqualTo("Generated update");
    }

    /*
     * Scenario Outline: PR-002 Default the target branch from the repository
     *   Given a valid Azure DevOps repository whose default branch is "main"
     *   And a CreatePullRequest with source "update-v2", blank target, and title "Update v2"
     *   When createPullRequest is called
     *   Then the provider request uses "main" as the target branch
     *   And the returned PullRequest has target branch "main"
     */
    @Test
    void pr002_defaultTargetBranchFromRepository() throws Exception {
        AzureCreatePullRequestRes prRes = loadJson("azure/create_pull_request.json", AzureCreatePullRequestRes.class);
        when(restTemplate.exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/pullrequests?api-version={apiVersion}"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(AzureCreatePullRequestRes.class),
                anyMap()
        )).thenReturn(new ResponseEntity<>(prRes, HttpStatus.CREATED));

        PullRequest result = azureDevOpsProvider.createPullRequest(
                azureRepository(), new CreatePullRequest("update-v2", null, "Update v2", null));

        assertThat(result.getTargetBranch()).isEqualTo("main");
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/pullrequests?api-version={apiVersion}"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(AzureCreatePullRequestRes.class),
                anyMap()
        );
        assertThat(((AzureCreatePullRequestReq) entityCaptor.getValue().getBody()).getTargetRefName())
                .isEqualTo("refs/heads/main");
    }

    /*
     * Scenario Outline: PR-003 Reject invalid Pull Request input before HTTP
     *   Given a valid Azure DevOps instance
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
        Repository repository = "nullRepo".equals(caseName) ? null : azureRepository();
        if (repository != null && "blankDefault".equals(caseName)) {
            repository.setDefaultBranch(null);
        }
        assertThatThrownBy(() -> azureDevOpsProvider.createPullRequest(repository, new CreatePullRequest(source, target, title, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.POST), any(), any(Class.class), anyMap());
    }

    /*
     * Scenario Outline: PR-004 Map provider authentication failure
     *   Given a valid Azure DevOps repository and CreatePullRequest
     *   And the provider create Pull Request API returns HTTP 401
     *   When createPullRequest is called
     *   Then GitProviderAuthenticationException is thrown
     */
    @Test
    void pr004_mapProviderAuthenticationFailure() {
        when(restTemplate.exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/pullrequests?api-version={apiVersion}"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(AzureCreatePullRequestRes.class),
                anyMap()
        )).thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> azureDevOpsProvider.createPullRequest(
                azureRepository(), new CreatePullRequest("update-v2", "main", "Update v2", null)))
                .isInstanceOf(GitProviderAuthenticationException.class);
    }

    /*
     * Scenario Outline: PR-005 Preserve provider HTTP errors
     *   Given a valid Azure DevOps repository and CreatePullRequest
     *   And the provider create Pull Request API returns a non-401 error with status and response body
     *   When createPullRequest is called
     *   Then GitClientException preserves the provider status and response body
     */
    @Test
    void pr005_preserveProviderHttpErrors() {
        when(restTemplate.exchange(
                eq(baseUrl + "/{projectId}/_apis/git/repositories/{repoId}/pullrequests?api-version={apiVersion}"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(AzureCreatePullRequestRes.class),
                anyMap()
        )).thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict",
                org.springframework.http.HttpHeaders.EMPTY, "{\"message\":\"exists\"}".getBytes(), null));

        assertThatThrownBy(() -> azureDevOpsProvider.createPullRequest(
                azureRepository(), new CreatePullRequest("update-v2", "main", "Update v2", null)))
                .isInstanceOf(GitClientException.class)
                .satisfies(ex -> {
                    GitClientException gitEx = (GitClientException) ex;
                    assertThat(gitEx.getCode()).isEqualTo(409);
                    assertThat(gitEx.getResponseBody()).contains("exists");
                });
    }

    private Repository azureRepository() {
        Repository repository = new Repository();
        repository.setOwnerId("project-1");
        repository.setId("repo-1");
        repository.setName("test-repo");
        repository.setDefaultBranch("main");
        return repository;
    }
}

