<!-- TOC -->

* [Git Handler Architecture](#git-handler-architecture)
    * [Overview](#overview)
    * [Exceptions](#exceptions)
    * [Core Components](#core-components)
        * [GitProvider Interface](#gitprovider-interface)
        * [Git Provider Extension](#git-provider-extension)
        * [Git Provider Factory](#git-provider-factory)
        * [Git Provider Model Extension](#git-provider-model-extension)
        * [Git Provider Custom Resource Reader](#git-provider-custom-resource-reader)
        * [GitProviderModelResourceType Enum](#gitprovidermodelresourcetype-enum)
    * [Model Resource Extensions Mechanism](#model-resource-extensions-mechanism)
        * [Repository and ProviderCustomResourceProperty](#repository-and-providercustomresourceproperty)
        * [How It Works](#how-it-works)
    * [Provider Structure](#provider-structure)
    * [How-To Guide](#how-to-guide)
        * [Extend a Core Git Resource for a Specific Provider](#extend-a-core-git-resource-for-a-specific-provider)
            * [Step 1: Create a Model Extension Class](#step-1-create-a-model-extension-class)
            * [Step 2: Register the Extension in Your Provider](#step-2-register-the-extension-in-your-provider)
            * [Step 3: Use the Custom Property in Provider Operations](#step-3-use-the-custom-property-in-provider-operations)
        * [Support Reading a New Custom Resource for a Specific Git Provider](#support-reading-a-new-custom-resource-for-a-specific-git-provider)
            * [Step 1: Create API Response Models and Mapper](#step-1-create-api-response-models-and-mapper)
            * [Step 2: Implement the Resource Listing Method](#step-2-implement-the-resource-listing-method)
            * [Step 3: Create and Register a Custom Resource Reader](#step-3-create-and-register-a-custom-resource-reader)
        * [Add a New Provider](#add-a-new-provider)
            * [Step 1: Create the Provider Implementation](#step-1-create-the-provider-implementation)
            * [Step 2: Create API Response Models](#step-2-create-api-response-models)
            * [Step 3: Add Provider Type to Enum](#step-3-add-provider-type-to-enum)
            * [Step 4: Register Provider in Factory](#step-4-register-provider-in-factory)
            * [Step 5: Add Extensions (Optional)](#step-5-add-extensions-optional)
            * [Step 6: Add Custom Resource Readers (Optional)](#step-6-add-custom-resource-readers-optional)
            * [Step 7: Test Your Implementation](#step-7-test-your-implementation)
    * [Summary](#summary)

<!-- TOC -->

# Git Handler Architecture

This package provides an abstraction layer for interacting with different Git providers (GitHub, GitLab, Bitbucket,
Azure DevOps) through a unified interface. The architecture is designed to be extensible, allowing providers to add
provider-specific properties and resources while maintaining a common core model.

## Overview

The git package (`org.opendatamesh.platform.git`) is organized into:

- **`provider/`**: Core provider interfaces and implementations (GitHub, GitLab, Bitbucket, Azure DevOps), and provider-specific credentials. The **factory** that creates provider instances is implemented by the **consuming application** (e.g. registry).
- **`model/`**: Shared domain models (Repository, Branch, Commit, Tag, User, Organization, RepositoryPointer, CreatePullRequest, PullRequest, etc.)
- **`git/`**: Low-level Git operations (clone, init, add, commit, push, selective branch/tag push, regular and pure-orphan branch creation, local branch merge, tag) and credential types for Git transport
- **`exceptions/`**: Exception hierarchy for Git and provider failures (see [Exceptions](#exceptions))
- **`client/`**: Optional REST client utilities and exceptions

## Exceptions

All Git-related failures use a common base and are handled consistently at the REST layer.

### Hierarchy

- **`GitException`** (extends `RuntimeException`) — Base for all git-layer failures. Allows a single catch point or global handling.
- **`GitOperationException`** — Low-level Git operation failures (clone, init, add, commit, push, pushBranch, pushTag, createAndCheckoutBranch, createAndCheckoutOrphanBranch, mergeBranch, tag, getHeadSha). Carries optional `operation` and `details`; typically wraps JGit/IO errors.
- **`GitProviderAuthenticationException`** — Thrown when provider API returns 401. Used so the application does not propagate 401 and log out the user.
- **`GitClientException`** — Provider HTTP API failures (4xx/5xx). Immutable; holds `code` and `responseBody`; supports an optional cause. Message is a readable summary (response body truncated when long).
- **`GitProviderConfigurationException`** — Invalid or unsupported provider configuration.

### REST Handling

The consuming application (e.g. the registry’s `ResponseExceptionHandler`) maps these to HTTP responses as needed:

| Exception | HTTP Status | Error code / name |
|-----------|-------------|---------------------|
| `GitOperationException` | 400 Bad Request | `GitOperationFailed` |
| `GitProviderAuthenticationException` | 400 Bad Request | `Git Provider Authentication Failed` |
| `GitClientException` | From exception’s `code` (e.g. 404, 403, 500) | `GitProviderError` |
| `GitProviderConfigurationException` | 400 Bad Request | (application-defined) |

Services may catch `GitOperationException` (or other git exceptions) and rethrow as needed to add context.

### Usage in Providers

- For **provider API** calls: on 401 throw `GitProviderAuthenticationException`; on other HTTP errors throw `GitClientException(statusCode, responseBody)` or `GitClientException(statusCode, responseBody, cause)`.
- For **low-level Git** (e.g. `GitOperationImpl`): throw `GitOperationException(operation, message)` or `GitOperationException(operation, message, cause)` so the operation name is available for logging and responses.

## Core Components

### GitProvider Interface

The `GitProvider` interface defines the contract for all Git provider implementations. It provides a unified set of
operations that all providers must support:

- **User Operations**: `getCurrentUser()` - Get authenticated user information
- **Organization Operations**: `listOrganizations()`, `getOrganization()`, `listMembers()`
- **Repository Operations**: `listRepositories()`, `getRepository()`, `createRepository()`
- **Pull Requests**: `createPullRequest(Repository, CreatePullRequest)` — Creates a same-repository Pull Request / Merge Request; caller must push the source branch first (typically via `pushBranch`); returns a `PullRequest` with `id` and `webUrl`
- **Repository Content**: `listCommits()`, `listBranches()`, `listTags()`
- **Low-level Git**: `gitOperation()` — Returns a `GitOperation` facade for clone, init, add, commit, legacy `push` (unchanged), selective `pushBranch` / `pushTag`, regular `createAndCheckoutBranch`, pure `createAndCheckoutOrphanBranch`, local `mergeBranch` (including unrelated histories with conflict rollback), tag, and getHeadSha (uses credentials from the provider)

Additionally, the interface provides an extension point for provider-specific resources:

- **`getProviderCustomResources()`**: Default method on `GitProvider` that returns an empty page; providers override it to
  retrieve provider-specific resources that don't exist in the standard model (e.g., Bitbucket projects, GitHub projects).
  Parameters: `customResourceType`, `parameters`, `pageable`; returns `Page<ProviderCustomResource>`.

Custom resource **definitions** (metadata about additional properties for standard resources, e.g. a "project" property
for Bitbucket repositories) are exposed via the **`GitProviderExtension`** interface (see below).

### Git Provider Extension

The **`GitProviderExtension`** interface is implemented by providers that extend standard model resources with
additional property definitions:

- **`getProviderCustomResourceDefinitions(GitProviderModelResourceType)`**: Returns a list of
  `ProviderCustomResourceDefinition` (name, type, required) for the given resource type (e.g. `REPOSITORY`).

Providers such as `BitbucketProvider` implement both `GitProvider` and `GitProviderExtension`; the consuming
application uses the same provider instance for both core operations and extension methods.

### Git Provider Factory (consuming application)

The consuming application (e.g. the registry) defines a `GitProviderFactory` interface and its implementation to
create provider instances. The factory uses an application-specific enum (e.g. `DataProductRepoProviderType`) to
determine which provider implementation to instantiate from this library:

- `GITHUB` → `GitHubProvider`
- `GITLAB` → `GitLabProvider`
- `BITBUCKET` → `BitbucketProvider`
- `AZURE` → `AzureDevOpsProvider`

Each provider is initialized with:

- `baseUrl`: The API base URL for the provider
- `restTemplate`: Spring's RestTemplate for HTTP requests
- `credential`: Authentication credentials (PAT, OAuth, etc.)

### Git Provider Model Extension

The `GitProviderModelExtension` interface allows providers to extend standard model resources with additional
properties. This is used when a provider requires extra information that isn't part of the core Git model.

**Example**: Bitbucket requires a "project" property when creating repositories, which is not part of the standard
Repository model.

**Interface methods**:

- `support(GitProviderModelResourceType resourceType)`: Indicates which resource type this extension supports
- `getCustomResourcesDefinitions()`: Returns the list of additional property definitions (name, type, required flag)

The extension mechanism works as follows:

1. A provider implementation implements `GitProviderExtension` and registers one or more `GitProviderModelExtension` instances
2. When `getProviderCustomResourceDefinitions(modelResourceType)` is called on the provider, it filters extensions by resource type
3. The matching extension returns the list of custom property definitions
4. These definitions are used by the API layer to validate and process provider-specific properties

### Git Provider Custom Resource Reader

The `GitProviderCustomResourceReader` interface enables providers to expose provider-specific resources that don't exist
in the standard Git model. These are resources unique to a particular provider (e.g., Bitbucket projects, GitHub
projects, GitLab groups).

**Interface methods**:

- `support(String resourceType)`: Indicates which custom resource type this reader supports
- `getCustomResources(MultiValueMap<String, String> parameters, Pageable pageable)`: Retrieves a paginated list of custom resources

**Example**: Bitbucket exposes a "project" custom resource type that can be listed and used when creating repositories.

### GitProviderModelResourceType Enum

This enum defines the standard Git model resource types that can be extended. Currently, it contains:

- `REPOSITORY`: The Repository resource type

This enum is used by `GitProviderModelExtension` to specify which resource type an extension applies to. Future resource
types (e.g., `BRANCH`, `COMMIT`) can be added as needed.

## Model Resource Extensions Mechanism

### Repository and ProviderCustomResourceProperty

The standard `Repository` model includes a `providerCustomResourceProperties` field of type
`List<ProviderCustomResourceProperty>`. This allows providers to attach additional properties to repository instances
without modifying the core model.

Each `ProviderCustomResourceProperty` contains:

- `name`: The property name (e.g., "project")
- `value`: A `JsonNode` containing the property value (flexible structure)

### How It Works

1. **Extension Definition**: A provider registers one or more classes that implement `GitProviderModelExtension` to
   define what additional properties are available for a resource type
2. **Property Definition**: The extension returns `ProviderCustomResourceDefinition` objects that specify:
    - Property name
    - Property type (e.g., "OBJECT", "STRING")
    - Whether the property is required
3. **Property Storage**: When creating or retrieving repositories, provider-specific properties are stored in the
   `providerCustomResourceProperties` list
4. **Provider Usage**: The provider implementation reads these properties when performing operations (e.g., using the "
   project" property when creating a Bitbucket repository)

## Provider Structure

Each provider implementation (e.g., `BitbucketProvider`, `GitHubProvider`) is organized as follows:

- **Main Provider Class**: Implements `GitProvider`; optionally implements `GitProviderExtension` when the provider exposes custom resource definitions (e.g. Bitbucket’s repository "project" property)
- **`resources/`**: Provider-specific API response models and mappers organized by operation:
    - `getcurrentuser/`
    - `listrepositories/`
    - `createrepository/`
    - `listbranches/`
    - `listcommits/`
    - `listtags/`
    - etc.
- **`modelextensions/`**: Model extension implementations (e.g., `BitbucketRepositoryExtension`)

## How-To Guide

This section provides step-by-step instructions for common extension tasks.

### Extend a Core Git Resource for a Specific Provider

When a provider requires additional properties for a standard Git resource (e.g., Repository), follow these steps:

#### Step 1: Create a Model Extension Class

Create a new class in your provider's `modelextensions/` package that implements `GitProviderModelExtension`:

```java
package org.opendatamesh.platform.git.provider.yourprovider.modelextensions;

import com.fasterxml.jackson.databind.node.JsonNodeType;
import org.opendatamesh.platform.git.model.ProviderCustomResourceDefinition;
import org.opendatamesh.platform.git.provider.GitProviderModelExtension;
import org.opendatamesh.platform.git.provider.GitProviderModelResourceType;

import java.util.List;

public class YourProviderRepositoryExtension implements GitProviderModelExtension {

    public static final String CUSTOM_PROPERTY = "customProperty";

    @Override
    public boolean support(GitProviderModelResourceType resourceType) {
        return GitProviderModelResourceType.REPOSITORY.equals(resourceType);
    }

    @Override
    public List<ProviderCustomResourceDefinition> getCustomResourcesDefinitions() {
        return List.of(
                new ProviderCustomResourceDefinition(
                        CUSTOM_PROPERTY,
                        JsonNodeType.STRING.name(),  // or OBJECT, NUMBER, etc.
                        true  // required or false
                )
        );
    }
}
```

#### Step 2: Register the Extension in Your Provider

In your provider class, implement `GitProviderExtension` and add the extension to the `registeredExtensions` list:

```java
import org.opendatamesh.platform.git.model.ProviderCustomResourceDefinition;

public class YourProviderProvider implements GitProvider, GitProviderExtension {

    private final List<GitProviderModelExtension> registeredExtensions = List.of(
            new YourProviderRepositoryExtension()
    );

    @Override
    public List<ProviderCustomResourceDefinition> getProviderCustomResourceDefinitions(
            GitProviderModelResourceType modelResourceType) {
        return this.registeredExtensions.stream()
                .filter(ext -> ext.support(modelResourceType))
                .findFirst()
                .map(GitProviderModelExtension::getCustomResourcesDefinitions)
                .orElse(List.of());
    }
}
```

#### Step 3: Use the Custom Property in Provider Operations

When implementing operations that use the extended resource, read the custom properties from
`Repository.getProviderCustomResourceProperties()` (may be null):

```java
import org.opendatamesh.platform.git.model.ProviderCustomResourceProperty;
import org.opendatamesh.platform.git.exceptions.GitProviderConfigurationException;
import com.fasterxml.jackson.databind.JsonNode;

@Override
public Repository createRepository(Repository repositoryToCreate) {
    List<ProviderCustomResourceProperty> customProps =
            repositoryToCreate.getProviderCustomResourceProperties();
    if (customProps == null) {
        customProps = List.of();
    }

    ProviderCustomResourceProperty customProp = customProps.stream()
            .filter(p -> YourProviderRepositoryExtension.CUSTOM_PROPERTY.equals(p.getName()))
            .findFirst()
            .orElseThrow(() -> new GitProviderConfigurationException("Custom property is required"));

    JsonNode propertyValue = customProp.getValue();
    // ... use propertyValue to build your provider's API request
}
```

**Note**: When reading repositories, you may also need to populate the `providerCustomResourceProperties` field by
mapping provider-specific data from the API response to the standard model.

### Support Reading a New Custom Resource for a Specific Git Provider

To expose provider-specific resources (e.g., projects, workspaces, teams) that don't exist in the standard Git model:

#### Step 1: Create API Response Models and Mapper

Create response models in `resources/listyourresource/` package to represent the provider's API response:

```java
// YourProviderListYourResourceRes.java - represents the API response
public class YourProviderListYourResourceRes {
    // fields matching provider's API response
}

// YourProviderListYourResourceMapper.java - maps to ProviderCustomResource
import org.opendatamesh.platform.git.model.ProviderCustomResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class YourProviderListYourResourceMapper {
    public static ProviderCustomResource toProviderCustomResource(
            YourProviderListYourResourceRes resourceRes) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode content = mapper.createObjectNode();
        // ... populate content from resourceRes

        return new ProviderCustomResource(
                resourceRes.getId(),   // identifier
                resourceRes.getName(), // displayName
                content                // JsonNode with additional properties
        );
    }
}
```

#### Step 2: Implement the Resource Listing Method

In your provider class, implement a method that fetches and returns the custom resources as a
`Page<ProviderCustomResource>` (e.g. using `PageImpl`):

```java
import org.opendatamesh.platform.git.model.ProviderCustomResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.MultiValueMap;
import java.util.List;

private Page<ProviderCustomResource> listYourResources(
        MultiValueMap<String, String> parameters,
        Pageable pageable) {
    // Build API request URL with pagination; extract filter parameters if needed; call provider API

    List<ProviderCustomResource> resources = response.getValues().stream()
            .map(YourProviderListYourResourceMapper::toProviderCustomResource)
            .toList();

    return new PageImpl<>(resources, pageable, totalCount);
}
```

#### Step 3: Create and Register a Custom Resource Reader

In your provider class, create and register a `GitProviderCustomResourceReader` and override
`getProviderCustomResources` (from `GitProvider`):

```java
import org.opendatamesh.platform.git.model.ProviderCustomResource;
import org.opendatamesh.platform.git.provider.GitProviderCustomResourceReader;
import org.opendatamesh.platform.git.exceptions.GitProviderConfigurationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.util.MultiValueMap;
import java.util.List;
import java.util.function.BiFunction;

public class YourProviderProvider implements GitProvider {

    private static final String YOUR_RESOURCE_TYPE = "yourResourceType";

    private final List<GitProviderCustomResourceReader> customResourceReaders = List.of(
            createCustomResourceReader(YOUR_RESOURCE_TYPE, this::listYourResources)
    );

    private GitProviderCustomResourceReader createCustomResourceReader(
            String resourceType,
            BiFunction<MultiValueMap<String, String>, Pageable, Page<ProviderCustomResource>> resourceSupplier) {
        return new GitProviderCustomResourceReader() {
            @Override
            public boolean support(String type) {
                return resourceType.equalsIgnoreCase(type);
            }

            @Override
            public Page<ProviderCustomResource> getCustomResources(
                    MultiValueMap<String, String> parameters,
                    Pageable pageable) {
                return resourceSupplier.apply(parameters, pageable);
            }
        };
    }

    @Override
    public Page<ProviderCustomResource> getProviderCustomResources(
            String customResourceType,
            MultiValueMap<String, String> parameters,
            Pageable pageable) {
        return this.customResourceReaders.stream()
                .filter(reader -> reader.support(customResourceType))
                .findFirst()
                .map(reader -> reader.getCustomResources(parameters, pageable))
                .orElseThrow(() -> new GitProviderConfigurationException(
                        "Unsupported resource type: " + customResourceType));
    }
}
```

**Note**: The resource type string (e.g., `"yourResourceType"`) should be meaningful and unique to your provider. This
is what clients will use to request the custom resource.

### Add a New Provider

To add support for an entirely new Git provider:

#### Step 1: Create the Provider Implementation

Create a new package `provider/yourprovider/` and implement the `GitProvider` interface. The constructor
accepts `GitProviderCredential` (from this library) and may throw `GitProviderConfigurationException`:

```java
package org.opendatamesh.platform.git.provider.yourprovider;

import org.opendatamesh.platform.git.model.Organization;
import org.opendatamesh.platform.git.model.User;
import org.opendatamesh.platform.git.provider.GitProvider;
import org.opendatamesh.platform.git.provider.GitProviderCredential;
import org.opendatamesh.platform.git.exceptions.GitProviderConfigurationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.client.RestTemplate;

public class YourProviderProvider implements GitProvider {

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final GitProviderCredential credential;

    public YourProviderProvider(String baseUrl, RestTemplate restTemplate, GitProviderCredential credential)
            throws GitProviderConfigurationException {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "https://api.yourprovider.com";
        this.restTemplate = restTemplate;
        this.credential = credential;
    }

    @Override
    public User getCurrentUser() { /* ... */ }

    @Override
    public Page<Organization> listOrganizations(Pageable page) { /* ... */ }

    // ... implement all other required methods of GitProvider
}
```

#### Step 2: Create API Response Models

For each operation, create response models in `resources/` subdirectories:

```
provider/yourprovider/
├── YourProviderProvider.java
└── resources/
    ├── getcurrentuser/
    │   ├── YourProviderGetCurrentUserRes.java
    │   └── YourProviderGetCurrentUserMapper.java
    ├── listrepositories/
    │   ├── YourProviderListRepositoriesRes.java
    │   └── YourProviderListRepositoriesMapper.java
    └── ... (other operations)
```

Create mapper classes to convert provider-specific responses to standard model objects.

#### Step 3: Add Provider Type to Enum (in consuming application)

Add your provider type to the consuming application’s enum (e.g. `DataProductRepoProviderType`).

#### Step 4: Register Provider in Factory (in consuming application)

Update the consuming application’s `GitProviderFactoryImpl` to instantiate your provider from this library:

```java
return switch (providerType) {
    case GITHUB -> new GitHubProvider(baseUrl, restTemplate, credential);
    case GITLAB -> new GitLabProvider(baseUrl, restTemplate, credential);
    case BITBUCKET -> new BitbucketProvider(baseUrl, restTemplate, credential);
    case AZURE -> new AzureDevOpsProvider(baseUrl, restTemplate, credential);
    case YOUR_PROVIDER -> new YourProviderProvider(baseUrl, restTemplate, credential);
};
```

#### Step 5: Add Extensions (Optional)

If your provider requires additional properties on standard resources, follow the steps in section **Extend a Core Git Resource for a Specific Provider** to create model extensions.

#### Step 6: Add Custom Resource Readers (Optional)

If your provider exposes unique resources, follow the steps in section **Support Reading a New Custom Resource for a Specific Git Provider** to create custom resource readers.

#### Step 7: Test Your Implementation

Create test classes to verify:

- All `GitProvider` methods work correctly
- Authentication is properly handled
- API responses are correctly mapped to standard models
- Custom properties and resources work as expected

## Summary

The git package architecture provides:

- **Unified Interface**: Common operations across all Git providers
- **Extensibility**: Providers can add custom properties and resources
- **Type Safety**: Enum-based resource types and extension points
- **Flexibility**: JsonNode-based property values allow for complex nested structures
- **Maintainability**: Clear separation of concerns with interfaces and implementations
- **Exception Hierarchy**: `GitException` base with `GitOperationException`, `GitProviderAuthenticationException`, and `GitClientException`, mapped to consistent HTTP responses by the consuming application’s REST layer

This design allows the system to support multiple Git providers while accommodating their unique requirements without
polluting the core model with provider-specific fields.
