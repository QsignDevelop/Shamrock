package com.tencent.mobileqq.fe

import android.content.Context
import com.tencent.mobileqq.sign.QQSecuritySign
import android.util.Log
import mqq.app.MobileQQ
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real FEKit implementation that forwards sign requests to external qsign server
 * This replaces the stub implementation in qqinterface
 */
class FEKit private constructor() {
    
    companion object {
        private const val TAG = "FEKit"
        
        // External qsign server URL - CHANGE THIS TO YOUR SERVER
        private var qsignServerUrl = "http://127.0.0.1:8080"
        
        // Singleton instance
        private var instance: FEKit? = null
        
        @JvmStatic
        fun getInstance(): FEKit {
            if (instance == null) {
                synchronized(FEKit::class.java) {
                    if (instance == null) {
                        instance = FEKit()
                    }
                }
            }
            return instance!!
        }
        
        fun setQSignServer(url: String) {
            qsignServerUrl = url.trimEnd('/')
        }
    }
    
    // Instance fields
    private var context: Context? = null
    private var uin: String? = null
    private var guid: String? = null
    private var o3did: String? = null
    private var q36: String? = null
    private var qua: String? = null
    private var initialized = false
    
    /**
     * Initialize FEKit with required parameters
     */
    fun init(
        ctx: Context,
        uin: String,
        guid: String,
        o3did: String,
        q36: String,
        qua: String
    ) {
        this.context = ctx
        this.uin = uin
        this.guid = guid
        this.o3did = o3did
        this.q36 = q36
        this.qua = qua
        this.initialized = true
        
        Log.i(TAG, "FEKit initialized for uin: $uin, guid: $guid")
    }
    
    /**
     * Get sign from external qsign server
     */
    fun getSign(cmd: String, buffer: ByteArray, seq: Int, uin: String): QQSecuritySign.SignResult {
        if (!initialized) {
            Log.e(TAG, "FEKit not initialized!")
            return QQSecuritySign.SignResult()
        }
        
        Log.d(TAG, "Getting sign for cmd=$cmd, uin=$uin, seq=$seq, bufferSize=${buffer.size}")
        
        return try {
            val result = requestSignFromServer(cmd, this.uin ?: uin, seq, buffer)
            if (result != null) {
                Log.d(TAG, "Sign obtained from server: tokenSize=${result.token?.size ?: 0}, signSize=${result.sign?.size ?: 0}")
                result
            } else {
                Log.w(TAG, "Server returned null, returning empty sign")
                QQSecuritySign.SignResult()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get sign: ${e.message}")
            e.printStackTrace()
            QQSecuritySign.SignResult()
        }
    }
    
    /**
     * Request sign from external qsign server
     */
    private fun requestSignFromServer(cmd: String, uin: String, seq: Int, buffer: ByteArray): QQSecuritySign.SignResult? {
        var connection: HttpURLConnection? = null
        
        try {
            val url = URL("$qsignServerUrl/sign?ver=1&cmd=${URLEncoder.encode(cmd, "UTF-8")}&seq=$seq&uin=${URLEncoder.encode(uin, "UTF-8")}")
            
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "QQ/9.2.85")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val hexBuffer = buffer.toHexString()
            val postData = "buffer=${URLEncoder.encode(hexBuffer, "UTF-8")}&qua=$qua&guid=$guid&o3did=$o3did&qimei36=$q36"
            
            connection.outputStream.write(postData.toByteArray(Charsets.UTF_8))
            connection.outputStream.flush()
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                
                return parseSignResponse(response.toString())
            } else {
                Log.e(TAG, "Server returned code: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        
        return null
    }
    
    /**
     * Parse JSON response from qsign server
     */
    private fun parseSignResponse(json: String): QQSecuritySign.SignResult? {
        try {
            val result = QQSecuritySign.SignResult()
            
            val tokenMatch = Regex(""""token"\s*:\s*"([^"]+)"""").find(json)
            if (tokenMatch != null) {
                result.token = tokenMatch.groupValues[1].hex2ByteArray()
            }
            
            val signMatch = Regex(""""sign"\s*:\s*"([^"]+)"""").find(json)
            if (signMatch != null) {
                result.sign = signMatch.groupValues[1].hex2ByteArray()
            }
            
            val extraMatch = Regex(""""extra"\s*:\s*"([^"]+)"""").find(json)
            if (extraMatch != null) {
                result.extra = extraMatch.groupValues[1].hex2ByteArray()
            }
            
            return if (result.token != null || result.sign != null) result else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${e.message}")
            return null
        }
    }
    
    /**
     * Get command whitelist
     */
    fun getCmdWhiteList(): List<String> {
        return listOf(
            "trpc.o3.ecdh_access.EcdhAccess.SsoSecureAccess",
            "trpc.o3.ecdh_access.EcdhAccess.SsoSecureA2Access",
            "wtlogin.exchange_emp",
            "MessageSvc.PbGetMsg",
            "OidbSvcTrpcTcp.0x1017_4",
            "OidbSvcTrpcTcp.0x1008_1",
            "OidbSvcTrpcTcp.0x1237_1",
            "trpc.msg.register_proxy.RegisterProxy.SsoSyncGroupMsg",
            "trpc.kuolie.gray_ctrl.GrayCtrl.SsoEntranceCtrl"
        )
    }
    
    // Extension functions
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
    
    private fun String.hex2ByteArray(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
