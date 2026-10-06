package com.ifmix.core.api.bff.api.customer.media

import com.ifmix.core.api.dto.storage.PresignDownloadResult
import com.ifmix.core.api.dto.storage.PresignUploadInput
import com.ifmix.core.api.dto.storage.PresignUploadResult
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.modules.media.StorageFacade
import com.ifmix.core.api.entity.common.ActorTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.reflect.full.declaredMemberFunctions

/**
 * [MediaController] 单测：仿 DemoControllerTest 模式（真实 [ActionContextFactory] + mock JWT +
 * MockHttpServletRequest；facade mock）。无 GlobalTxRunner——presign 两 action 原本就不包事务。
 */
class MediaControllerTest {

    private val projectId = "ifmix-app"
    private val reqId = "req-media-1"

    private val jwt = mock<AuthJwtService>()
    private val facade = mock<StorageFacade>()

    private lateinit var objectMapper: ObjectMapper
    private lateinit var controller: MediaController

    @BeforeEach
    fun setUp() {
        objectMapper = JsonMapper.builder().build()
        val ctxFactory = ActionContextFactory(jwt, strict = true)
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = UUID.randomUUID().toString(),
                projectId = projectId,
                actorType = ActorTypes.CUSTOMER,
                tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER,
            ),
        )
        controller = MediaController(ctxFactory, facade)
    }

    private fun validMeta() = RequestMeta(reqId = reqId, projectId = projectId, accessToken = "SECRET-customer")

    private inline fun <reified T : Any> body(input: Map<String, Any?>): ApiRequestBody<T> =
        ApiRequestBody(validMeta(), objectMapper.convertValue(input, T::class.java))

    private fun request() = MockHttpServletRequest().apply { LogContext.start(this) }

    // ===== 每个 action 一条成功用例 =====

    @Test
    fun `presignUpload success returns envelope with reqId echo and no tx`() {
        val mediaId = UUID.randomUUID()
        whenever(facade.presignUpload(any(), any())).thenReturn(
            PresignUploadResult(mediaId = mediaId, uploadUrl = "https://u", imageKey = "image/p/x", downloadUrl = "https://d"),
        )
        val resp = controller.presignUpload(request(), body(mapOf("prefix" to "antique_scan", "contentType" to 10)))

        assertEquals("200000", resp.body!!.code)
        assertEquals(mediaId, resp.body!!.data?.mediaId)
        assertEquals("https://u", resp.body!!.data?.uploadUrl)
        assertEquals("image/p/x", resp.body!!.data?.imageKey)
        assertEquals(reqId, resp.body!!.reqId, "Envelope.reqId must echo meta.reqId on success")
        val captor = argumentCaptor<PresignUploadInput>()
        verify(facade).presignUpload(any(), captor.capture())
        assertEquals("antique_scan", captor.firstValue.prefix)
        assertEquals(10, captor.firstValue.contentType)
        LogContext.clear()
    }

    @Test
    fun `presignDownload success with optional durationSeconds defaulting null`() {
        whenever(facade.presignDownload(any(), any())).thenReturn(PresignDownloadResult(url = "https://signed"))
        val resp = controller.presignDownload(request(), body(mapOf("imageKey" to "image/p/x/k.jpg")))

        assertEquals("200000", resp.body!!.code)
        assertEquals("https://signed", resp.body!!.data?.url)
        assertEquals(reqId, resp.body!!.reqId)
        LogContext.clear()
    }

    // ===== 关键错误用例 =====

    @Test
    fun `presignUpload missing required field fails with 400 semantic`() {
        val ex = assertThrows(ApiError::class.java) {
            controller.presignUpload(request(), ApiRequestBody(validMeta(), null))
        }
        // input 缺段 → requireInput 400000；非法 shape 在生产环境由 Spring 反序列化边界映射同一 400000
        assertEquals(ErrorCode.INVALID_REQUEST, ex.errorCode)
        LogContext.clear()
    }

    @Test
    fun `facade ApiError propagates unchanged`() {
        whenever(facade.presignDownload(any(), any()))
            .thenThrow(ApiError(ErrorCode.INVALID_REQUEST, "invalid objectKey"))
        val ex = assertThrows(ApiError::class.java) {
            controller.presignDownload(request(), body(mapOf("imageKey" to "bad")))
        }
        assertEquals(ErrorCode.INVALID_REQUEST, ex.errorCode)
        assertEquals("400000", ex.errorCode.externalCode)
        LogContext.clear()
    }

    @Test
    fun `unauthenticated request rejected by ActionContextFactory`() {
        val meta = RequestMeta(reqId = reqId, projectId = projectId) // 无 token
        val ex = assertThrows(ApiError::class.java) {
            controller.presignUpload(
                request(),
                ApiRequestBody<PresignUploadInput>(meta, null),
            )
        }
        assertEquals(ErrorCode.UNAUTHORIZED, ex.errorCode)
        verifyNoInteractions(facade)
        LogContext.clear()
    }

    // ===== 命名一致性护栏 =====

    @Test
    fun `routes one-to-one with controller companion constants`() {
        val expected = setOf("m_media_file_presignUpload", "m_media_file_presignDownload")
        val postings = MediaController::class.declaredMemberFunctions
            .filter { it.annotations.any { a -> a is org.springframework.web.bind.annotation.PostMapping } }
        assertEquals(2, postings.size)
        val names = postings.map { f ->
            val path = f.annotations.filterIsInstance<org.springframework.web.bind.annotation.PostMapping>().single().value.first()
            val operationId = f.annotations.filterIsInstance<io.swagger.v3.oas.annotations.Operation>().single().operationId
            assertEquals(path, operationId, "path must equal operationId: " + path)
            path
        }
        assertEquals(expected, names.toSet())
        names.forEach { n ->
            val segs = n.split("_")
            assertEquals(4, segs.size, "four-segment format: " + n)
            assertEquals("m", segs[0], "all media actions are mutations: " + n)
            assertEquals("media", segs[1], "module segment: " + n)
            assertEquals("file", segs[2], "resource segment (media→file，2026-10-06 定名): " + n)
        }
        // companion 常量与路由 path 同源
        assertEquals("m_media_file_presignUpload", MediaController.REQNAME_PRESIGN_UPLOAD)
        assertEquals("m_media_file_presignDownload", MediaController.REQNAME_PRESIGN_DOWNLOAD)
    }
}
