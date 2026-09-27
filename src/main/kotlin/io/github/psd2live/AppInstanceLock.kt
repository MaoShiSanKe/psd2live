package io.github.psd2live

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One GUI per workspace store: two editors would write the same recovery history and race for the
 * MCP port. The OS drops the lock however the process ends, so a crash never leaves it stale.
 */
internal class AppInstanceLock private constructor(
	private val channel: FileChannel,
	private val lock: FileLock,
) : AutoCloseable {
	override fun close() {
		runCatching { lock.release() }
		runCatching { channel.close() }
	}

	companion object {
		/** The lock on [directory], or null while another process holds it. */
		fun acquire(directory: Path): AppInstanceLock? {
			Files.createDirectories(directory)
			val channel = FileChannel.open(directory.resolve(".instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
			val lock = try {
				channel.tryLock()
			} catch (_: OverlappingFileLockException) {
				null
			} catch (failure: IOException) {
				channel.close()
				throw failure
			}
			if (lock == null) {
				channel.close()
				return null
			}
			return AppInstanceLock(channel, lock)
		}
	}
}
