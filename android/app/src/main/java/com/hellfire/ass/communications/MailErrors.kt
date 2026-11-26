package com.hellfire.ass.communications

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.net.ssl.SSLException

/** Owner-facing reason for a mail failure, so an expired app password does not look like a network outage. */
object MailErrors {
    fun code(error: Throwable): String {
        val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(8).toList()
        return when {
            chain.any { it is AuthenticationFailedException } -> "AUTHENTICATION_FAILED_CHECK_APP_PASSWORD"
            chain.any { it is SSLException } -> "TLS_FAILED_CHECK_SERVER"
            chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException } -> "NETWORK_UNAVAILABLE"
            chain.any { it is IOException } -> "CONNECTION_FAILED"
            else -> "CONNECTION_OR_CREDENTIALS_UNAVAILABLE"
        }
    }
}
