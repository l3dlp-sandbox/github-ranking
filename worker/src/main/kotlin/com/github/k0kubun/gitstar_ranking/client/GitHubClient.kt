package com.github.k0kubun.gitstar_ranking.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.k0kubun.gitstar_ranking.core.Repository
import com.github.k0kubun.gitstar_ranking.core.User
import java.net.SocketTimeoutException
import java.time.temporal.ChronoUnit
import javax.ws.rs.BadRequestException
import javax.ws.rs.ClientErrorException
import javax.ws.rs.ForbiddenException
import javax.ws.rs.InternalServerErrorException
import javax.ws.rs.NotAuthorizedException
import javax.ws.rs.NotFoundException
import javax.ws.rs.RedirectionException
import javax.ws.rs.ServerErrorException
import javax.ws.rs.ServiceUnavailableException
import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.core.HttpHeaders
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import net.jodah.failsafe.Failsafe
import net.jodah.failsafe.Policy
import net.jodah.failsafe.RetryPolicy
import org.glassfish.jersey.client.ClientProperties
import org.slf4j.LoggerFactory

private const val PAGE_SIZE = 100
private const val API_ENDPOINT = "https://api.github.com"

// https://docs.github.com/en/rest/reference/rate-limit#get-rate-limit-status-for-the-authenticated-user
private data class RateLimitResponse(val resources: RateLimitResources)
private data class RateLimitResources(val core: RateLimit)
private data class RateLimit(val limit: Int, val remaining: Int, val reset: Long)

// https://docs.github.com/en/rest/reference/users#get-a-user
// https://docs.github.com/en/rest/reference/users#list-users
private data class UserResponse(
    val id: Long,
    val type: String,
    val login: String,
    val avatarUrl: String,
) {
    val user = User(id = id, type = type, login = login, avatarUrl = avatarUrl)
}

// https://docs.github.com/en/rest/reference/repos#list-repositories-for-a-user
private data class RepositoryResponse(
    val id: Long,
    val name: String,
    val fullName: String,
    val owner: RepositoryOwner,
    val description: String?,
    val fork: Boolean,
    val homepage: String?,
    val language: String?,
    val stargazersCount: Long,
) {
    val repository = Repository(
        id = id,
        ownerId = owner.id.toInt(),
        name = name,
        fullName = fullName,
        description = description,
        fork = fork,
        homepage = homepage,
        stargazersCount = stargazersCount.toInt(),
        language = language,
    )
}
private data class RepositoryOwner(val id: Long)

class GitHubClient(private val token: String) {
    private val logger = LoggerFactory.getLogger(GitHubClient::class.simpleName)
    private val objectMapper = ObjectMapper().registerModule(KotlinModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val client = ClientBuilder.newBuilder()
        .property(ClientProperties.CONNECT_TIMEOUT, 5000)
        .property(ClientProperties.READ_TIMEOUT, 30000)
        .register(JacksonJsonProvider(objectMapper))
        .build().target(API_ENDPOINT)
    private val retryPolicy = RetryPolicy<Any>()
        .handleIf { e ->
            when (e) {
                is SocketTimeoutException -> true
                is ServerErrorException -> e.response.status == 502
                else -> false
            }
        }
        .withBackoff(1, 8, ChronoUnit.SECONDS)
        .withMaxRetries(3)

    var rateLimitRemaining = requestGet<RateLimitResponse>("/rate_limit").resources.core.remaining

    fun getLogin(userId: Long): String {
        return requestGet<UserResponse>("/user/$userId").login
    }

    fun getUserWithLogin(login: String): User {
        return requestGet<UserResponse>("/users/$login").user
    }

    fun getUsersSince(since: Long): List<User> {
        return requestGet<List<UserResponse>>("/users", params = mapOf("since" to since)).map { it.user }
    }

    fun getPublicRepos(userId: Long): List<Repository> {
        return paginateAll(paginateRepositories(userId)).map { it.repository }
    }

    private fun paginateRepositories(userId: Long): (Int) -> List<RepositoryResponse> = { page ->
        requestGet("/user/$userId/repos", params = mapOf("page" to page, "per_page" to PAGE_SIZE))
    }

    private fun <T> paginateAll(getPage: (Int) -> List<T>): List<T> {
        var page = 1
        val results = mutableListOf<T>()
        while (true) {
            val result = getPage(page)
            results.addAll(result)

            if (result.size < PAGE_SIZE) break
            logger.debug("Paginate page: $page, size: ${results.size}")
            page++
        }
        return results
    }

    private inline fun <reified T> requestGet(
        path: String,
        params: Map<String, Any> = emptyMap(),
    ): T {
        return failsafe(retryPolicy) {
            client.path(path)
                .let {
                    params.toList().fold(it) { target, (key, value) ->
                        target.queryParam(key, value.toString())
                    }
                }
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "bearer $token")
                .get(Response::class.java)
                .apply {
                    getHeaderString("X-RateLimit-Remaining")?.also {
                        rateLimitRemaining = it.toInt()
                    }
                    checkStatus(this)
                }
                .run {
                    objectMapper.readValue(readEntity(String::class.java), object : TypeReference<T>() {})
                }
        }
    }

    private fun checkStatus(response: Response) {
        when (response.status) {
            Response.Status.BAD_REQUEST.statusCode -> throw BadRequestException(response)
            Response.Status.UNAUTHORIZED.statusCode -> throw NotAuthorizedException(response)
            Response.Status.FORBIDDEN.statusCode -> throw ForbiddenException(response)
            Response.Status.NOT_FOUND.statusCode -> throw NotFoundException(response)
            Response.Status.INTERNAL_SERVER_ERROR.statusCode -> throw InternalServerErrorException(response)
            Response.Status.SERVICE_UNAVAILABLE.statusCode -> throw ServiceUnavailableException(response)
            in 300..399 -> throw RedirectionException(response)
            in 400..499 -> throw ClientErrorException(response)
            in 500..599 -> throw ServerErrorException(response)
        }
    }

    private fun <R, T : R> failsafe(vararg policies: Policy<R>, call: () -> T): T {
        return Failsafe.with(*policies).get(call)
    }
}
