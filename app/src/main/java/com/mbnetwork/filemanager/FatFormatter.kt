package com.mbnetwork.filemanager

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Formats a whole USB drive as ONE FAT32 partition (MBR, type 0x0C), like Windows "Quick format".
 * Works on any drive (NTFS / exFAT / FAT / unknown) – everything on it is erased.
 */
object FatFormatter {
    private const val START_LBA = 2048L          // 1 MiB alignment
    private const val RESERVED = 32

    /** [progress] gets 0..100. */
    fun format(dev: BlockDeviceDriver, label: String, progress: (Int) -> Unit) {
        val bs = dev.blockSize
        require(bs in listOf(512, 1024, 2048, 4096)) { "Unsupported sector size $bs" }
        val totalSectors = dev.blocks + 1                 // blocks = last LBA
        val partSectors = totalSectors - START_LBA
        require(partSectors > 65536) { "Drive is too small for FAT32" }
        require(partSectors <= 0xFFFFFFFFL) { "Drive is too large for FAT32" }
        val sizeBytes = partSectors * bs
        val gb = 1024L * 1024 * 1024
        val bytesPerCluster = when { sizeBytes <= 8 * gb -> 4096; sizeBytes <= 16 * gb -> 8192; sizeBytes <= 32 * gb -> 16384; else -> 32768 }
        val spc = (bytesPerCluster / bs).coerceAtLeast(1)

        // FAT size (Microsoft formula)
        val tmp1 = partSectors - RESERVED
        val tmp2 = (256L * spc + 2) / 2 * (bs / 512)
        val fatSz = (tmp1 + tmp2 - 1) / tmp2

        fun buf() = ByteBuffer.allocate(bs).order(ByteOrder.LITTLE_ENDIAN)
        fun write(lba: Long, b: ByteBuffer) { b.clear(); dev.write(lba, b) }

        // 1) MBR with one FAT32 (LBA) partition
        val mbr = buf()
        val e = 446
        mbr.put(e + 4, 0x0C)
        mbr.putInt(e + 8, START_LBA.toInt())
        mbr.putInt(e + 12, partSectors.toInt())
        mbr.put(e + 1, 0xFE.toByte()); mbr.put(e + 2, 0xFF.toByte()); mbr.put(e + 3, 0xFF.toByte())
        mbr.put(e + 5, 0xFE.toByte()); mbr.put(e + 6, 0xFF.toByte()); mbr.put(e + 7, 0xFF.toByte())
        mbr.put(510, 0x55); mbr.put(511, 0xAA.toByte())
        write(0, mbr)
        progress(2)

        // 2) boot sector
        val vol = label.uppercase().filter { it.code in 32..126 && it !in "\"*+,./:;<=>?[\\]|" }.take(11).padEnd(11, ' ')
        val boot = buf()
        boot.put(0, 0xEB.toByte()); boot.put(1, 0x58); boot.put(2, 0x90.toByte())
        "MSWIN4.1".toByteArray().forEachIndexed { i, c -> boot.put(3 + i, c) }
        boot.putShort(11, bs.toShort())
        boot.put(13, spc.toByte())
        boot.putShort(14, RESERVED.toShort())
        boot.put(16, 2)                                  // number of FATs
        boot.put(21, 0xF8.toByte())                      // media
        boot.putShort(24, 63); boot.putShort(26, 255)
        boot.putInt(28, START_LBA.toInt())               // hidden sectors
        boot.putInt(32, partSectors.toInt())
        boot.putInt(36, fatSz.toInt())
        boot.putInt(44, 2)                               // root cluster
        boot.putShort(48, 1)                             // FSInfo
        boot.putShort(50, 6)                             // backup boot
        boot.put(64, 0x80.toByte()); boot.put(66, 0x29)
        boot.putInt(67, Random.nextInt())
        vol.toByteArray().forEachIndexed { i, c -> boot.put(71 + i, c) }
        "FAT32   ".toByteArray().forEachIndexed { i, c -> boot.put(82 + i, c) }
        boot.put(bs - 2, 0x55); boot.put(bs - 1, 0xAA.toByte())

        val fsInfo = buf()
        fsInfo.putInt(0, 0x41615252); fsInfo.putInt(484, 0x61417272)
        fsInfo.putInt(488, -1); fsInfo.putInt(492, 3); fsInfo.putInt(508, 0xAA550000.toInt())

        // clear reserved area, then write boot/fsinfo + backups
        val zero = ByteBuffer.allocate(bs * 64)
        writeZeros(dev, START_LBA, RESERVED.toLong(), zero)
        write(START_LBA, boot); write(START_LBA + 1, fsInfo)
        write(START_LBA + 6, boot); write(START_LBA + 7, fsInfo)
        progress(5)

        // 3) both FATs: zero, then first entries
        val fat1 = START_LBA + RESERVED
        for (f in 0 until 2) {
            val start = fat1 + f * fatSz
            writeZeros(dev, start, fatSz, zero) { done -> progress(5 + ((f * fatSz + done) * 90 / (2 * fatSz)).toInt()) }
            val first = buf()
            first.putInt(0, 0x0FFFFFF8); first.putInt(4, 0x0FFFFFFF); first.putInt(8, 0x0FFFFFFF)
            write(start, first)
        }

        // 4) root directory cluster with volume label entry
        val dataStart = fat1 + 2 * fatSz
        writeZeros(dev, dataStart, spc.toLong(), zero)
        val root = buf()
        vol.toByteArray().forEachIndexed { i, c -> root.put(i, c) }
        root.put(11, 0x08)
        write(dataStart, root)
        progress(100)
    }

    private fun writeZeros(dev: BlockDeviceDriver, lba: Long, count: Long, zero: ByteBuffer, onProgress: ((Long) -> Unit)? = null) {
        val per = (zero.capacity() / dev.blockSize).toLong()
        var done = 0L
        while (done < count) {
            val n = minOf(per, count - done)
            zero.clear(); zero.limit((n * dev.blockSize).toInt())
            dev.write(lba + done, zero)
            done += n
            onProgress?.invoke(done)
        }
    }

    /** Detects the file system from a partition's first sector. */
    fun detect(dev: BlockDeviceDriver): String {
        val b = ByteBuffer.allocate(dev.blockSize).order(ByteOrder.LITTLE_ENDIAN)
        dev.read(0, b)
        fun at(lba: Long): String {
            val s = ByteBuffer.allocate(dev.blockSize); dev.read(lba, s)
            val a = s.array()
            return when {
                String(a, 3, 8).startsWith("NTFS") -> "NTFS"
                String(a, 3, 8).startsWith("EXFAT") -> "exFAT"
                String(a, 82, 5) == "FAT32" -> "FAT32"
                String(a, 54, 5).startsWith("FAT") -> "FAT16"
                else -> "Unknown"
            }
        }
        val first = at(0)
        if (first != "Unknown") return first
        // MBR: look at first partition
        val a = b.array()
        if ((a[510].toInt() and 0xFF) == 0x55 && (a[511].toInt() and 0xFF) == 0xAA) {
            val start = b.getInt(446 + 8).toLong() and 0xFFFFFFFFL
            if (start > 0 && start < dev.blocks) return at(start)
        }
        return "Unknown"
    }

    /** Sets the FAT32 volume label (boot sector + root directory entry). [partStart] = partition first LBA. */
    fun setLabel(dev: BlockDeviceDriver, partStart: Long, label: String) {
        val bs = dev.blockSize
        val vol = label.uppercase().filter { it.code in 32..126 && it !in "\"*+,./:;<=>?[\\]|" }.take(11).padEnd(11, ' ')
        val boot = ByteBuffer.allocate(bs).order(ByteOrder.LITTLE_ENDIAN); dev.read(partStart, boot)
        require(String(boot.array(), 82, 5) == "FAT32") { "Not a FAT32 drive" }
        vol.toByteArray().forEachIndexed { i, c -> boot.put(71 + i, c) }
        boot.clear(); dev.write(partStart, boot)
        boot.clear(); dev.write(partStart + 6, boot) // backup boot sector
        // root directory: replace or add the volume-label entry in the first root cluster
        val spc = boot.get(13).toInt() and 0xFF
        val reserved = boot.getShort(14).toInt() and 0xFFFF
        val fats = boot.get(16).toInt() and 0xFF
        val fatSz = boot.getInt(36).toLong() and 0xFFFFFFFFL
        val rootClus = boot.getInt(44).toLong() and 0xFFFFFFFFL
        val rootLba = partStart + reserved + fats * fatSz + (rootClus - 2) * spc
        val dir = ByteBuffer.allocate(bs * spc); dev.read(rootLba, dir)
        val a = dir.array()
        var slot = -1
        var i = 0
        while (i < a.size) {
            val first = a[i].toInt() and 0xFF
            val attr = a[i + 11].toInt() and 0xFF
            if (first != 0xE5 && first != 0 && attr == 0x08) { slot = i; break }
            if (first == 0 && slot < 0) { slot = i; break }
            i += 32
        }
        require(slot >= 0) { "No space for the label in the root folder" }
        for (k in 0 until 32) a[slot + k] = 0
        vol.toByteArray().copyInto(a, slot)
        a[slot + 11] = 0x08
        dir.clear(); dev.write(rootLba, dir)
    }
}
