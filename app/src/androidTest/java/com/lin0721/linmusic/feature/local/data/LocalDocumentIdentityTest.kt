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

    @Test
    fun externalStorageRecoveryUsesParentBoundary() {
        val root = DocumentsContract.buildTreeDocumentUri(authority, "primary:Music/foo")
        val child = DocumentsContract.buildDocumentUri(
            authority,
            "primary:Music/foo/track.mp3"
        )
        val siblingWithPrefix = DocumentsContract.buildDocumentUri(
            authority,
            "primary:Music/foobar/track.mp3"
        )

        assertEquals(true, localDocumentBelongsToDirectory(root, child))
        assertEquals(false, localDocumentBelongsToDirectory(root, siblingWithPrefix))
    }

    @Test
    fun opaqueProviderRecoveryRequiresExactTreeRoot() {
        val root = DocumentsContract.buildTreeDocumentUri("com.example.documents", "root-a")
        val sameTree = Uri.parse("content://com.example.documents/tree/root-a/document/root-a%2Ftrack.mp3")
        val similarTree = Uri.parse("content://com.example.documents/tree/root-ab/document/root-ab%2Ftrack.mp3")
        val standalone = Uri.parse("content://com.example.documents/document/root-a%2Ftrack.mp3")

        assertEquals(true, localDocumentBelongsToDirectory(root, sameTree))
        assertEquals(false, localDocumentBelongsToDirectory(root, similarTree))
        assertEquals(false, localDocumentBelongsToDirectory(root, standalone))
    }

    @Test
    fun directoryIdNormalizesQueryAndFragment() {
        val canonical = DocumentsContract.buildTreeDocumentUri(
            "com.example.documents",
            "primary:Music"
        )
        val decorated = Uri.parse("${canonical}?session=temporary#fragment")

        assertEquals(canonical.toString(), localDirectoryId(decorated))
    }

    @Test
    fun volumeRootIncludesItsDocumentsButNotAnotherVolume() {
        val root = DocumentsContract.buildTreeDocumentUri(authority, "primary:")
        assertEquals(true, localDocumentBelongsToDirectory(root,
            DocumentsContract.buildDocumentUri(authority, "primary:Music/song.mp3")))
        assertEquals(false, localDocumentBelongsToDirectory(root,
            DocumentsContract.buildDocumentUri(authority, "secondary:Music/song.mp3")))
    }
}
