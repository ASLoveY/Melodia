package com.lin0721.linmusic.feature.local.data

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDocumentIdentityTest {
    private val authority = "com.android.externalstorage.documents"

    @Test
    fun fileAndNestedDirectoryAccessShareIdentity() {
        val documentId = "primary:Music/Artist/歌曲 one.mp3"
        val singleFile = DocumentsContract.buildDocumentUri(authority, documentId)
        val musicTree = DocumentsContract.buildTreeDocumentUri(authority, "primary:Music")
        val artistTree = DocumentsContract.buildTreeDocumentUri(authority, "primary:Music/Artist")

        assertEquals(localDocumentKey(singleFile), localDocumentKey(DocumentsContract.buildDocumentUriUsingTree(musicTree, documentId)))
        assertEquals(localDocumentKey(singleFile), localDocumentKey(DocumentsContract.buildDocumentUriUsingTree(artistTree, documentId)))
    }

    @Test
    fun sameNameInDifferentDirectoriesRemainsDistinct() {
        val first = DocumentsContract.buildDocumentUri(authority, "primary:Music/One/song.mp3")
        val second = DocumentsContract.buildDocumentUri(authority, "primary:Music/Two/song.mp3")

        assertNotEquals(localDocumentKey(first), localDocumentKey(second))
    }

    @Test
    fun otherContentUrisKeepTheirOriginalIdentity() {
        val uri = Uri.parse("content://media/external/audio/media/123")
        assertEquals(uri.toString(), localDocumentKey(uri))
    }
}
