package mega.privacy.android.app.fragments.homepage

import android.content.Context
import android.os.Handler
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mega.privacy.android.app.fragments.homepage.photos.PhotoNodeItem
import mega.privacy.android.app.listeners.BaseListener
import mega.privacy.android.app.utils.FileUtil
import mega.privacy.android.app.utils.ThumbnailUtilsLollipop.getThumbFolder
import mega.privacy.android.app.utils.Util
import nz.mega.sdk.*
import nz.mega.sdk.MegaApiJava.*
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject

class TypedFilesRepository @Inject constructor(
    private val megaApi: MegaApiAndroid,
    @ApplicationContext private val context: Context
) {

    private var order = ORDER_DEFAULT_ASC
    private var type = FILE_TYPE_DEFAULT

    // LinkedHashMap guarantees that the index order of elements is consistent with
    // the order of putting. Moreover, it has a quick element search[O(1)] (for
    // the callback of megaApi.getThumbnail())
    private val fileNodesMap: MutableMap<Any, NodeItem> = LinkedHashMap()
    private val savedFileNodesMap: MutableMap<Any, NodeItem> = LinkedHashMap()

    private var waitingForRefresh = false

    private val _fileNodeItems = MutableLiveData<List<NodeItem>>()
    val fileNodeItems: LiveData<List<NodeItem>> = _fileNodeItems

    suspend fun getFiles(type: Int, order: Int) {
        this.type = type
        this.order = order

        withContext(Dispatchers.IO) {
            saveAndClearData()
            getNodeItems()

            // Update LiveData must in main thread
            withContext(Dispatchers.Main) {
                emitFiles()
            }
        }
    }

    fun emitFiles() {
        synchronized(this) {
            _fileNodeItems.value = ArrayList(fileNodesMap.values)
        }
    }

    /**
     * Save some field values (e.g. "selected") which do not exist in the raw MegaNode data.
     * Restore these values in event of querying the raw data again
     */
    private fun saveAndClearData() {
        synchronized(this) {
            savedFileNodesMap.clear()
            fileNodesMap.toMap(savedFileNodesMap)
            fileNodesMap.clear()
        }
    }

    private fun getThumbnail(node: MegaNode): File? {
        val thumbFile = File(
            getThumbFolder(context),
            node.base64Handle.plus(FileUtil.JPG_EXTENSION)
        )

        return if (thumbFile.exists()) {
            thumbFile
        } else {
            if (node.hasThumbnail()) {
                megaApi.getThumbnail(node, thumbFile.absolutePath, object : BaseListener(context) {
                    override fun onRequestFinish(
                        api: MegaApiJava,
                        request: MegaRequest,
                        e: MegaError
                    ) {
                        if (e.errorCode != MegaError.API_OK) return

                        request.let {
                            fileNodesMap[it.nodeHandle]?.apply {
                                thumbnail = thumbFile.absoluteFile
                                uiDirty = true
                            }
                        }

                        refreshLiveData()
                    }
                })
            }

            null
        }
    }

    /**
     * Throttle for updating the Photos LiveData
     */
    private fun refreshLiveData() {
        if (waitingForRefresh) return
        waitingForRefresh = true

        Handler().postDelayed(
            {
                waitingForRefresh = false
                emitFiles()
            }, UPDATE_DATA_THROTTLE_TIME
        )
    }

    private fun getNodeItems() {
        var lastModifyDate: LocalDate? = null

        for (node in getMegaNodes()) {
            val thumbnail = getThumbnail(node)
            val modifyDate = Util.fromEpoch(node.modificationTime)
            val dateString = DateTimeFormatter.ofPattern("MMM uuuu").format(modifyDate)

            // Photo "Month-Year" section headers
            if (type == FILE_TYPE_PHOTO && (lastModifyDate == null
                        || YearMonth.from(lastModifyDate) != YearMonth.from(
                    modifyDate
                ))
            ) {
                lastModifyDate = modifyDate
                // RandomUUID() can ensure non-repetitive values in practical purpose
                synchronized(this) {
                    fileNodesMap[UUID.randomUUID()] = PhotoNodeItem(
                        PhotoNodeItem.TYPE_TITLE,
                        -1,
                        null,
                        -1,
                        dateString,
                        null,
                        false
                    )
                }
            }

            val selected = savedFileNodesMap[node.handle]?.selected ?: false
            var nodeItem: NodeItem?

            if (type == FILE_TYPE_PHOTO) {
                nodeItem = PhotoNodeItem(
                    PhotoNodeItem.TYPE_PHOTO,
                    -1,
                    node,
                    -1,
                    dateString,
                    thumbnail,
                    selected
                )
            } else {
                nodeItem = NodeItem(
                    node,
                    -1,
                    type == FILE_TYPE_VIDEO,
                    dateString,
                    thumbnail,
                    selected
                )
            }

            synchronized(this) {
                fileNodesMap[node.handle] = nodeItem
            }
        }
    }

    private fun getMegaNodes(): List<MegaNode> {
        return megaApi.searchByType(order, type, SEARCH_TARGET_ROOTNODE)
    }

    companion object {
        private const val UPDATE_DATA_THROTTLE_TIME =
            500L   // 500ms, user can see the update of photos instantly
    }
}
