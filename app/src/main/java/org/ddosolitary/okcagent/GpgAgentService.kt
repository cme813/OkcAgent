package org.ddosolitary.okcagent

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.openintents.openpgp.OpenPgpDecryptionResult
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi.*
import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception
import java.lang.IllegalStateException
import java.net.Socket

const val EXTRA_GPG_ARGS = "org.ddosolitary.okcagent.extra.GPG_ARGS"
private const val NOTIFICATION_ID_GPG = 2

class GpgAgentService : AgentService() {
	private class OutputWrapper(private val stream: OutputStream) : OutputStream() {
		private var written = false

		override fun write(b: Int) {
			written = true
			stream.write(b)
		}

		override fun write(b: ByteArray) {
			written = true
			stream.write(b)
		}

		override fun write(b: ByteArray, off: Int, len: Int) {
			written = true
			stream.write(b, off, len)
		}

		override fun flush() {
			stream.flush()
		}

		override fun close() {}
	}

	private fun writeString(output: OutputStream, str: String) {
		val strBuf = str.toByteArray(Charsets.UTF_8)
		val lenBuf = byteArrayOf(
			(strBuf.size shr 8).toByte(),
			(strBuf.size and Byte.MAX_VALUE.toInt()).toByte()
		)
		output.write(lenBuf)
		output.write(strBuf)
		output.flush()
	}

	private fun handleSigResult(res: OpenPgpSignatureResult, output: OutputStream): Boolean {
		val resStr = when (res.result) {
			-1 -> "RESULT_NO_SIGNATURE"
			0 -> "RESULT_INVALID_SIGNATURE"
			1 -> "RESULT_VALID_KEY_CONFIRMED"
			2 -> "RESULT_KEY_MISSING"
			3 -> "RESULT_VALID_KEY_UNCONFIRMED"
			4 -> "RESULT_INVALID_KEY_REVOKED"
			5 -> "RESULT_INVALID_KEY_EXPIRED"
			6 -> "RESULT_INVALID_KEY_INSECURE"
			else -> "N/A"
		}
		writeString(
			output,
			getString(R.string.msg_signature_from).format(res.primaryUserId ?: "N/A")
		)
		writeString(output, getString(R.string.msg_signature_result).format(resStr))
		return res.result == 1 || res.result == 3
	}

	override fun getErrorMessage(intent: Intent): String? {
		return intent.getParcelableExtra<OpenPgpError>(RESULT_ERROR)?.message
	}

	override fun runAgent(port: Int, intent: Intent) {
		var controlSocket: Socket? = null
		var controlOutput: OutputStream? = null
		var success = true
		try {
			controlSocket = Socket("127.0.0.1", port)
			controlOutput = controlSocket.getOutputStream().also { it.write(0) }
			val args = GpgArguments.parse(
				this,
				intent.getStringArrayExtra(EXTRA_GPG_ARGS)!!
					.map { Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8) }
			)
			for (msg in args.warnings) {
				writeString(controlOutput, msg)
			}
			val keyId = getSharedPreferences(getString(R.string.pref_main), Context.MODE_PRIVATE)
				.getLong(getString(R.string.key_gpg_key), -1)
			Socket("127.0.0.1", port).use { inputSocket ->
				inputSocket.getOutputStream().let {
					it.write(1)
					writeString(it, if (args.arguments.isNotEmpty()) args.arguments.last() else "")
				}
				val input = inputSocket.getInputStream()
				Socket("127.0.0.1", port).use { outputSocket ->
					val output = OutputWrapper(outputSocket.getOutputStream().also {
						it.write(2)
						writeString(it, args.options["output"] ?: "")
					})
					val lock = Object()
					var connRes = false
					GpgApi(this) { res ->
						if (!res) showError(this@GpgAgentService, R.string.error_connect)
						connRes = res
						synchronized(lock) { lock.notify() }
					}.use { api ->
						api.connect()
						synchronized(lock) { lock.wait() }
						if (!connRes) throw IllegalStateException()
						val reqIntent = Intent()
						if (args.options.containsKey("armor"))
							reqIntent.putExtra(EXTRA_REQUEST_ASCII_ARMOR, true)
						args.options["compress-level"]?.let {
							reqIntent.putExtra(EXTRA_ENABLE_COMPRESSION, it.toInt() > 0)
						}
						args.options["set-filename"]?.let {
							reqIntent.putExtra(EXTRA_ORIGINAL_FILENAME, it)
						}
						when {
							args.options.containsKey("clear-sign") -> {
								reqIntent.action = ACTION_CLEARTEXT_SIGN
								reqIntent.putExtra(EXTRA_SIGN_KEY_ID, keyId)
								val resIntent =
									callApi({ api.executeApi(it, input, output) }, reqIntent, port)
								if (resIntent == null) {
									success = false
									return@runAgent
								}
								Unit
							}
							args.options.containsKey("detach-sign") -> {
								reqIntent.action = ACTION_DETACHED_SIGN
								reqIntent.putExtra(EXTRA_SIGN_KEY_ID, keyId)
								val resIntent =
									callApi({ api.executeApi(it, input, null) }, reqIntent, port)
								if (resIntent == null) {
									success = false
									return@runAgent
								}
								output.write(resIntent.getByteArrayExtra(RESULT_DETACHED_SIGNATURE)!!)
							}
							args.options.containsKey("encrypt") -> {
								val recipient = args.options["recipient"]
								if (recipient == null) {
									throw Exception(
										getString(R.string.error_required_option).format("recipient")
									)
								} else {
									reqIntent.putExtra(EXTRA_USER_IDS, arrayOf(recipient))
								}
								if (args.options.containsKey("sign")) {
									reqIntent.action = ACTION_SIGN_AND_ENCRYPT
									reqIntent.putExtra(EXTRA_SIGN_KEY_ID, keyId)
								} else {
									reqIntent.action = ACTION_ENCRYPT
								}
								val resIntent =
									callApi({ api.executeApi(it, input, output) }, reqIntent, port)
								if (resIntent == null) {
									success = false
									return@runAgent
								}
								Unit
							}
							args.options.containsKey("verify") -> {
								reqIntent.action = ACTION_DECRYPT_VERIFY
								if (args.arguments.size > 1) {
									Socket("127.0.0.1", port).use { sigSocket ->
										sigSocket.getOutputStream().let {
											it.write(1)
											writeString(it, args.arguments[0])
										}
										reqIntent.putExtra(
											EXTRA_DETACHED_SIGNATURE,
											sigSocket.getInputStream().readBytes()
										)
									}
								}
								val resIntent =
									callApi({ api.executeApi(it, input, null) }, reqIntent, port)
								if (resIntent == null) {
									success = false
									return@runAgent
								}
								if (!handleSigResult(
										resIntent.getParcelableExtra(RESULT_SIGNATURE)!!,
										controlOutput
									)
								) success = false
								Unit
							}
							args.options.containsKey("decrypt") -> {
								reqIntent.action = ACTION_DECRYPT_VERIFY
								val resIntent = callApi(
									{ api.executeApi(it, input, output) },
									reqIntent, port
								)
								if (resIntent == null) {
									success = false
									return@runAgent
								}
								if (!handleSigResult(
										resIntent.getParcelableExtra(RESULT_SIGNATURE)!!,
										controlOutput
									)
								) success = false
								val decRes: OpenPgpDecryptionResult =
									resIntent.getParcelableExtra(RESULT_DECRYPTION)!!
								val resStr = when (decRes.result) {
									-1 -> "RESULT_NOT_ENCRYPTED"
									0 -> "RESULT_INSECURE"
									1 -> "RESULT_ENCRYPTED"
									else -> "N/A"
								}
								if (decRes.result != 1) success = false
								writeString(
									controlOutput,
									getString(R.string.msg_decryption_result).format(resStr)
								)
							}
							else -> throw Exception(getString(R.string.error_gpg_no_action))
						}
					}
				}
			}
		} catch (e: Exception) {
			success = false
			controlOutput?.let {
				writeString(it, "Error: %s".format(e.message))
			}
		} finally {
			controlOutput?.write(byteArrayOf(0, 0, if (success) 0 else 1))
			controlSocket?.close()
			checkThreadExit(port)
		}
	}

	override fun onCreate() {
		super.onCreate()
		val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_SERVICE)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentTitle(getString(R.string.notification_gpg_title))
			.setContentText(getString(R.string.notification_gpg_content))
			.build()
		startForeground(NOTIFICATION_ID_GPG, notification)
	}
}