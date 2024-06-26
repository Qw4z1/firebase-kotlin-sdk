/*
 * Copyright (c) 2020 GitLive Ltd.  Use of this source code is governed by the Apache 2.0 license.
 */

package dev.gitlive.firebase.firestore

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseApp
import dev.gitlive.firebase.FirebaseException
import dev.gitlive.firebase.externals.getApp
import dev.gitlive.firebase.firestore.externals.MemoryCacheSettings
import dev.gitlive.firebase.firestore.externals.PersistentCacheSettings
import dev.gitlive.firebase.firestore.externals.QueryConstraint
import dev.gitlive.firebase.firestore.externals.addDoc
import dev.gitlive.firebase.firestore.externals.and
import dev.gitlive.firebase.firestore.externals.clearIndexedDbPersistence
import dev.gitlive.firebase.firestore.externals.connectFirestoreEmulator
import dev.gitlive.firebase.firestore.externals.deleteDoc
import dev.gitlive.firebase.firestore.externals.doc
import dev.gitlive.firebase.firestore.externals.getDoc
import dev.gitlive.firebase.firestore.externals.getDocFromCache
import dev.gitlive.firebase.firestore.externals.getDocFromServer
import dev.gitlive.firebase.firestore.externals.getDocs
import dev.gitlive.firebase.firestore.externals.getDocsFromCache
import dev.gitlive.firebase.firestore.externals.getDocsFromServer
import dev.gitlive.firebase.firestore.externals.initializeFirestore
import dev.gitlive.firebase.firestore.externals.memoryEagerGarbageCollector
import dev.gitlive.firebase.firestore.externals.memoryLocalCache
import dev.gitlive.firebase.firestore.externals.memoryLruGarbageCollector
import dev.gitlive.firebase.firestore.externals.onSnapshot
import dev.gitlive.firebase.firestore.externals.or
import dev.gitlive.firebase.firestore.externals.orderBy
import dev.gitlive.firebase.firestore.externals.persistentLocalCache
import dev.gitlive.firebase.firestore.externals.query
import dev.gitlive.firebase.firestore.externals.refEqual
import dev.gitlive.firebase.firestore.externals.setDoc
import dev.gitlive.firebase.firestore.externals.setLogLevel
import dev.gitlive.firebase.firestore.externals.writeBatch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.promise
import kotlin.js.Json
import kotlin.js.json
import dev.gitlive.firebase.externals.FirebaseApp as JsFirebaseApp
import dev.gitlive.firebase.firestore.externals.Firestore as JsFirestore
import dev.gitlive.firebase.firestore.externals.CollectionReference as JsCollectionReference
import dev.gitlive.firebase.firestore.externals.DocumentChange as JsDocumentChange
import dev.gitlive.firebase.firestore.externals.DocumentReference as JsDocumentReference
import dev.gitlive.firebase.firestore.externals.DocumentSnapshot as JsDocumentSnapshot
import dev.gitlive.firebase.firestore.externals.FieldPath as JsFieldPath
import dev.gitlive.firebase.firestore.externals.Query as JsQuery
import dev.gitlive.firebase.firestore.externals.QuerySnapshot as JsQuerySnapshot
import dev.gitlive.firebase.firestore.externals.SnapshotMetadata as JsSnapshotMetadata
import dev.gitlive.firebase.firestore.externals.Transaction as JsTransaction
import dev.gitlive.firebase.firestore.externals.WriteBatch as JsWriteBatch
import dev.gitlive.firebase.firestore.externals.collection as jsCollection
import dev.gitlive.firebase.firestore.externals.collectionGroup as jsCollectionGroup
import dev.gitlive.firebase.firestore.externals.disableNetwork as jsDisableNetwork
import dev.gitlive.firebase.firestore.externals.documentId as jsDocumentId
import dev.gitlive.firebase.firestore.externals.enableNetwork as jsEnableNetwork
import dev.gitlive.firebase.firestore.externals.endAt as jsEndAt
import dev.gitlive.firebase.firestore.externals.endBefore as jsEndBefore
import dev.gitlive.firebase.firestore.externals.limit as jsLimit
import dev.gitlive.firebase.firestore.externals.runTransaction as jsRunTransaction
import dev.gitlive.firebase.firestore.externals.startAfter as jsStartAfter
import dev.gitlive.firebase.firestore.externals.startAt as jsStartAt
import dev.gitlive.firebase.firestore.externals.updateDoc as jsUpdate
import dev.gitlive.firebase.firestore.externals.where as jsWhere

actual val Firebase.firestore get() =
    rethrow { FirebaseFirestore(NativeFirebaseFirestoreWrapper(getApp())) }

actual fun Firebase.firestore(app: FirebaseApp) =
    rethrow { FirebaseFirestore(NativeFirebaseFirestoreWrapper(app.js)) }

actual data class NativeFirebaseFirestore(val js: JsFirestore)

internal actual class NativeFirebaseFirestoreWrapper internal constructor(
    private val createNative: NativeFirebaseFirestoreWrapper.() -> NativeFirebaseFirestore
){

    internal actual constructor(native: NativeFirebaseFirestore) : this({ native })
    internal constructor(app: JsFirebaseApp) : this(
        {
            NativeFirebaseFirestore(
                initializeFirestore(app, settings.js).also {
                    emulatorSettings?.run {
                        connectFirestoreEmulator(it, host, port)
                    }
                }
            )
        }
    )

    private data class EmulatorSettings(val host: String, val port: Int)

    actual var settings: FirebaseFirestoreSettings = FirebaseFirestoreSettings.Builder().build()
        set(value) {
            if (lazyNative.isInitialized()) {
                throw IllegalStateException("FirebaseFirestore has already been started and its settings can no longer be changed. You can only call setFirestoreSettings() before calling any other methods on a FirebaseFirestore object.")
            } else {
                field = value
            }
        }
    private var emulatorSettings: EmulatorSettings? = null

    // initializeFirestore must be called before any call, including before `getFirestore()`
    // To allow settings to be updated, we defer creating the wrapper until the first call to `native`
    private val lazyNative = lazy {
        createNative()
    }
    actual val native: NativeFirebaseFirestore by lazyNative
    private val js get() = native.js

    actual fun collection(collectionPath: String) = rethrow { NativeCollectionReference(jsCollection(js, collectionPath)) }

    actual fun collectionGroup(collectionId: String) = rethrow { NativeQuery(jsCollectionGroup(js, collectionId)) }

    actual fun document(documentPath: String) = rethrow { NativeDocumentReference(doc(js, documentPath)) }

    actual fun batch() = rethrow { NativeWriteBatch(writeBatch(js)) }

    actual fun setLoggingEnabled(loggingEnabled: Boolean) =
        rethrow { setLogLevel( if(loggingEnabled) "error" else "silent") }

    actual suspend fun <T> runTransaction(func: suspend NativeTransaction.() -> T) =
        rethrow { jsRunTransaction(js, { GlobalScope.promise { NativeTransaction(it).func() } } ).await() }

    actual suspend fun clearPersistence() =
        rethrow { clearIndexedDbPersistence(js).await() }

    actual fun useEmulator(host: String, port: Int) = rethrow {
        settings = firestoreSettings(settings) {
            this.host = "$host:$port"
        }
        emulatorSettings = EmulatorSettings(host, port)
    }

    actual suspend fun disableNetwork() {
        rethrow { jsDisableNetwork(js).await() }
    }

    actual suspend fun enableNetwork() {
        rethrow { jsEnableNetwork(js).await() }
    }
}

val FirebaseFirestore.js: JsFirestore get() = native.js

actual data class FirebaseFirestoreSettings(
    actual val sslEnabled: Boolean,
    actual val host: String,
    actual val cacheSettings: LocalCacheSettings,
) {

    actual companion object {
        actual val CACHE_SIZE_UNLIMITED: Long = -1L
        internal actual val DEFAULT_HOST: String = "firestore.googleapis.com"
        internal actual val MINIMUM_CACHE_BYTES: Long = 1 * 1024 * 1024
        // According to documentation, default JS Firestore cache size is 40MB, not 100MB
        internal actual val DEFAULT_CACHE_SIZE_BYTES: Long = 40 * 1024 * 1024
    }

    actual class Builder internal constructor(
        actual var sslEnabled: Boolean,
        actual var host: String,
        actual var cacheSettings: LocalCacheSettings,
    ) {

        actual constructor() : this(
            true,
            DEFAULT_HOST,
            persistentCacheSettings {  },
        )
        actual constructor(settings: FirebaseFirestoreSettings) : this(settings.sslEnabled, settings.host, settings.cacheSettings)

        actual fun build(): FirebaseFirestoreSettings = FirebaseFirestoreSettings(sslEnabled, host, cacheSettings)
    }

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    val js: Json get() = json().apply {
        set("ssl", sslEnabled)
        set("host", host)
        set("localCache",
        when (cacheSettings) {
            is LocalCacheSettings.Persistent -> persistentLocalCache(
                json(
                    "cacheSizeBytes" to cacheSettings.sizeBytes
                ).asDynamic() as PersistentCacheSettings
            )
            is LocalCacheSettings.Memory -> {
                val garbageCollecorSettings = when (val garbageCollectorSettings = cacheSettings.garbaseCollectorSettings) {
                    is MemoryGarbageCollectorSettings.Eager -> memoryEagerGarbageCollector()
                    is MemoryGarbageCollectorSettings.LRUGC -> memoryLruGarbageCollector(json("cacheSizeBytes" to garbageCollectorSettings.sizeBytes))
                }
                memoryLocalCache(json("garbageCollector" to garbageCollecorSettings).asDynamic() as MemoryCacheSettings)
            }
        })
    }
}

actual fun firestoreSettings(
    settings: FirebaseFirestoreSettings?,
    builder: FirebaseFirestoreSettings.Builder.() -> Unit
): FirebaseFirestoreSettings = FirebaseFirestoreSettings.Builder().apply {
    settings?.let {
        sslEnabled = it.sslEnabled
        host = it.host
        cacheSettings = it.cacheSettings
    }
}.apply(builder).build()

internal val SetOptions.js: Json get() = when (this) {
    is SetOptions.Merge -> json("merge" to true)
    is SetOptions.Overwrite -> json("merge" to false)
    is SetOptions.MergeFields -> json("mergeFields" to fields.toTypedArray())
    is SetOptions.MergeFieldPaths -> json("mergeFields" to encodedFieldPaths.toTypedArray())
}

@PublishedApi
internal actual class NativeWriteBatch(val js: JsWriteBatch) {

    actual fun setEncoded(
        documentRef: DocumentReference,
        encodedData: Any,
        setOptions: SetOptions
    ): NativeWriteBatch = rethrow { js.set(documentRef.js, encodedData, setOptions.js) }.let { this }

    actual fun updateEncoded(documentRef: DocumentReference, encodedData: Any): NativeWriteBatch = rethrow { js.update(documentRef.js, encodedData) }
        .let { this }

    actual fun updateEncodedFieldsAndValues(
        documentRef: DocumentReference,
        encodedFieldsAndValues: List<Pair<String, Any?>>
    ): NativeWriteBatch = rethrow {
        encodedFieldsAndValues.performUpdate { field, value, moreFieldsAndValues ->
            js.update(documentRef.js, field, value, *moreFieldsAndValues)
        }
    }.let { this }

    actual fun updateEncodedFieldPathsAndValues(
        documentRef: DocumentReference,
        encodedFieldsAndValues: List<Pair<EncodedFieldPath, Any?>>
    ): NativeWriteBatch = rethrow {
        encodedFieldsAndValues.performUpdate { field, value, moreFieldsAndValues ->
            js.update(documentRef.js, field, value, *moreFieldsAndValues)
        }
    }.let { this }

    actual fun delete(documentRef: DocumentReference) =
        rethrow { js.delete(documentRef.js) }
            .let { this }

    actual suspend fun commit() = rethrow { js.commit().await() }
}

val WriteBatch.js get() = native.js

@PublishedApi
internal actual class NativeTransaction(val js: JsTransaction) {

    actual fun setEncoded(
        documentRef: DocumentReference,
        encodedData: Any,
        setOptions: SetOptions
    ): NativeTransaction = rethrow {
        js.set(documentRef.js, encodedData, setOptions.js)
    }
        .let { this }

    actual fun updateEncoded(documentRef: DocumentReference, encodedData: Any): NativeTransaction = rethrow { js.update(documentRef.js, encodedData) }
        .let { this }

    actual fun updateEncodedFieldsAndValues(
        documentRef: DocumentReference,
        encodedFieldsAndValues: List<Pair<String, Any?>>
    ): NativeTransaction = rethrow {
        encodedFieldsAndValues.performUpdate { field, value, moreFieldsAndValues ->
            js.update(documentRef.js, field, value, *moreFieldsAndValues)
        }
    }.let { this }

    actual fun updateEncodedFieldPathsAndValues(
        documentRef: DocumentReference,
        encodedFieldsAndValues: List<Pair<EncodedFieldPath, Any?>>
    ): NativeTransaction = rethrow {
        encodedFieldsAndValues.performUpdate { field, value, moreFieldsAndValues ->
            js.update(documentRef.js, field, value, *moreFieldsAndValues)
        }
    }.let { this }

    actual fun delete(documentRef: DocumentReference) =
        rethrow { js.delete(documentRef.js) }
            .let { this }

    actual suspend fun get(documentRef: DocumentReference) =
        rethrow { NativeDocumentSnapshot(js.get(documentRef.js).await()) }
}

val Transaction.js get() = native.js

/** A class representing a platform specific Firebase DocumentReference. */
actual typealias NativeDocumentReferenceType = JsDocumentReference

@PublishedApi
internal actual class NativeDocumentReference actual constructor(actual val nativeValue: NativeDocumentReferenceType) {
    val js: NativeDocumentReferenceType = nativeValue

    actual val id: String
        get() = rethrow { js.id }

    actual val path: String
        get() = rethrow { js.path }

    actual val parent: NativeCollectionReference
        get() = rethrow { NativeCollectionReference(js.parent) }

    actual fun collection(collectionPath: String) = rethrow { NativeCollectionReference(jsCollection(js, collectionPath)) }

    actual suspend fun get(source: Source) = rethrow { NativeDocumentSnapshot( js.get(source).await()) }

    actual val snapshots: Flow<NativeDocumentSnapshot> get() = snapshots()

    actual fun snapshots(includeMetadataChanges: Boolean) = callbackFlow<NativeDocumentSnapshot>  {
        val unsubscribe = onSnapshot(
            js,
            json("includeMetadataChanges" to includeMetadataChanges),
            { trySend(NativeDocumentSnapshot(it)) },
            { close(errorToException(it)) }
        )
        awaitClose { unsubscribe() }
    }

    actual suspend fun setEncoded(encodedData: Any, setOptions: SetOptions) = rethrow {
        setDoc(js, encodedData, setOptions.js).await()
    }

    actual suspend fun updateEncoded(encodedData: Any) = rethrow { jsUpdate(js, encodedData).await() }

    actual suspend fun updateEncodedFieldsAndValues(encodedFieldsAndValues: List<Pair<String, Any?>>) {
        rethrow {
            encodedFieldsAndValues.takeUnless { encodedFieldsAndValues.isEmpty() }
                ?.performUpdate { field, value, moreFieldsAndValues ->
                    jsUpdate(js, field, value, *moreFieldsAndValues)
                }
                ?.await()
        }
    }

    actual suspend fun updateEncodedFieldPathsAndValues(encodedFieldsAndValues: List<Pair<EncodedFieldPath, Any?>>) {
        rethrow {
            encodedFieldsAndValues.takeUnless { encodedFieldsAndValues.isEmpty() }
                ?.performUpdate { field, value, moreFieldsAndValues ->
                    jsUpdate(js, field, value, *moreFieldsAndValues)
                }?.await()
        }
    }

    actual suspend fun delete() = rethrow { deleteDoc(js).await() }

    override fun equals(other: Any?): Boolean =
        this === other || other is NativeDocumentReference && refEqual(nativeValue, other.nativeValue)
    override fun hashCode(): Int = nativeValue.hashCode()
    override fun toString(): String = "DocumentReference(path=$path)"
}

val DocumentReference.js get() = native.js

@PublishedApi
internal actual open class NativeQuery(open val js: JsQuery)

actual open class Query internal actual constructor(nativeQuery: NativeQuery) {

    constructor(js: JsQuery) : this(NativeQuery(js))

    open val js: JsQuery = nativeQuery.js

    actual suspend fun get(source: Source) =  rethrow { QuerySnapshot(js.get(source).await()) }

    actual fun limit(limit: Number) = Query(query(js, jsLimit(limit)))

    internal actual fun where(filter: Filter): Query = Query(
        query(js, filter.toQueryConstraint())
    )

    private fun Filter.toQueryConstraint(): QueryConstraint = when (this) {
        is Filter.And -> and(*filters.map { it.toQueryConstraint() }.toTypedArray())
        is Filter.Or -> or(*filters.map { it.toQueryConstraint() }.toTypedArray())
        is Filter.Field -> {
            val value = when (constraint) {
                is WhereConstraint.ForNullableObject -> constraint.safeValue
                is WhereConstraint.ForObject -> constraint.safeValue
                is WhereConstraint.ForArray -> constraint.safeValues.toTypedArray()
            }
            jsWhere(field, constraint.filterOp, value)
        }
        is Filter.Path -> {
            val value = when (constraint) {
                is WhereConstraint.ForNullableObject -> constraint.safeValue
                is WhereConstraint.ForObject -> constraint.safeValue
                is WhereConstraint.ForArray -> constraint.safeValues.toTypedArray()
            }
            jsWhere(path.js, constraint.filterOp, value)
        }
    }

    private val WhereConstraint.filterOp: String get() = when (this) {
        is WhereConstraint.EqualTo -> "=="
        is WhereConstraint.NotEqualTo -> "!="
        is WhereConstraint.LessThan -> "<"
        is WhereConstraint.LessThanOrEqualTo -> "<="
        is WhereConstraint.GreaterThan -> ">"
        is WhereConstraint.GreaterThanOrEqualTo -> ">="
        is WhereConstraint.ArrayContains -> "array-contains"
        is WhereConstraint.ArrayContainsAny -> "array-contains-any"
        is WhereConstraint.InArray -> "in"
        is WhereConstraint.NotInArray -> "not-in"
    }

    internal actual fun _orderBy(field: String, direction: Direction) = rethrow {
        Query(query(js, orderBy(field, direction.jsString)))
    }

    internal actual fun _orderBy(field: FieldPath, direction: Direction) = rethrow {
        Query(query(js, orderBy(field.js, direction.jsString)))
    }

    internal actual fun _startAfter(document: DocumentSnapshot) = rethrow { Query(query(js, jsStartAfter(document.js))) }

    internal actual fun _startAfter(vararg fieldValues: Any) = rethrow { Query(query(js, jsStartAfter(*fieldValues))) }

    internal actual fun _startAt(document: DocumentSnapshot) = rethrow { Query(query(js, jsStartAt(document.js))) }

    internal actual fun _startAt(vararg fieldValues: Any) = rethrow { Query(query(js, jsStartAt(*fieldValues))) }

    internal actual fun _endBefore(document: DocumentSnapshot) = rethrow { Query(query(js, jsEndBefore(document.js))) }

    internal actual fun _endBefore(vararg fieldValues: Any) = rethrow { Query(query(js, jsEndBefore(*fieldValues))) }

    internal actual fun _endAt(document: DocumentSnapshot) = rethrow { Query(query(js, jsEndAt(document.js))) }

    internal actual fun _endAt(vararg fieldValues: Any) = rethrow { Query(query(js, jsEndAt(*fieldValues))) }

    actual val snapshots get() = callbackFlow<QuerySnapshot> {
        val unsubscribe = rethrow {
            onSnapshot(
                js,
                { trySend(QuerySnapshot(it)) },
                { close(errorToException(it)) }
            )
        }
        awaitClose { rethrow { unsubscribe() } }
    }

    actual fun snapshots(includeMetadataChanges: Boolean) = callbackFlow<QuerySnapshot> {
        val unsubscribe = rethrow {
            onSnapshot(
                js,
                json("includeMetadataChanges" to includeMetadataChanges),
                { trySend(QuerySnapshot(it)) },
                { close(errorToException(it)) }
            )
        }
        awaitClose { rethrow { unsubscribe() } }
    }
}

@PublishedApi
internal actual class NativeCollectionReference(override val js: JsCollectionReference) : NativeQuery(js) {

    actual val path: String
        get() =  rethrow { js.path }

    actual val document get() = rethrow { NativeDocumentReference(doc(js)) }

    actual val parent get() = rethrow { js.parent?.let{ NativeDocumentReference(it) } }

    actual fun document(documentPath: String) = rethrow { NativeDocumentReference(doc(js, documentPath)) }

    actual suspend fun addEncoded(data: Any) = rethrow {
        NativeDocumentReference(addDoc(js, data).await())
    }
}

val CollectionReference.js get() = native.js

actual class FirebaseFirestoreException(cause: Throwable, val code: FirestoreExceptionCode) : FirebaseException(code.toString(), cause)

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
actual val FirebaseFirestoreException.code: FirestoreExceptionCode get() = code

actual class QuerySnapshot(val js: JsQuerySnapshot) {
    actual val documents
        get() = js.docs.map { DocumentSnapshot(NativeDocumentSnapshot(it)) }
    actual val documentChanges
        get() = js.docChanges().map { DocumentChange(it) }
    actual val metadata: SnapshotMetadata get() = SnapshotMetadata(js.metadata)
}

actual class DocumentChange(val js: JsDocumentChange) {
    actual val document: DocumentSnapshot
        get() = DocumentSnapshot(NativeDocumentSnapshot(js.doc))
    actual val newIndex: Int
        get() = js.newIndex
    actual val oldIndex: Int
        get() = js.oldIndex
    actual val type: ChangeType
        get() = ChangeType.values().first { it.jsString == js.type }
}

@PublishedApi
internal actual class NativeDocumentSnapshot(val js: JsDocumentSnapshot) {

    actual val id get() = rethrow { js.id }
    actual val reference get() = rethrow { NativeDocumentReference(js.ref) }

    actual fun getEncoded(field: String, serverTimestampBehavior: ServerTimestampBehavior): Any? = rethrow {
        js.get(field, getTimestampsOptions(serverTimestampBehavior))
    }

    actual fun encodedData(serverTimestampBehavior: ServerTimestampBehavior): Any? = rethrow {
        js.data(getTimestampsOptions(serverTimestampBehavior))
    }

    actual fun contains(field: String) = rethrow { js.get(field) != undefined }
    actual val exists get() = rethrow { js.exists() }
    actual val metadata: SnapshotMetadata get() = SnapshotMetadata(js.metadata)

    fun getTimestampsOptions(serverTimestampBehavior: ServerTimestampBehavior) =
        json("serverTimestamps" to serverTimestampBehavior.name.lowercase())
}

val DocumentSnapshot.js get() = native.js

actual class SnapshotMetadata(val js: JsSnapshotMetadata) {
    actual val hasPendingWrites: Boolean get() = js.hasPendingWrites
    actual val isFromCache: Boolean get() = js.fromCache
}

actual class FieldPath private constructor(val js: JsFieldPath) {

    actual companion object {
        actual val documentId = FieldPath(jsDocumentId())
    }
    actual constructor(vararg fieldNames: String) : this(dev.gitlive.firebase.firestore.rethrow {
        JsFieldPath(*fieldNames)
    })
    actual val documentId: FieldPath get() = FieldPath.documentId
    actual val encoded: EncodedFieldPath = js
    override fun equals(other: Any?): Boolean = other is FieldPath && js.isEqual(other.js)
    override fun hashCode(): Int = js.hashCode()
    override fun toString(): String = js.toString()
}

actual typealias EncodedFieldPath = JsFieldPath

//actual data class FirebaseFirestoreSettings internal constructor(
//    val cacheSizeBytes: Number? = undefined,
//    val host: String? = undefined,
//    val ssl: Boolean? = undefined,
//    var timestampsInSnapshots: Boolean? = undefined,
//    var enablePersistence: Boolean = false
//)

actual enum class FirestoreExceptionCode {
    OK,
    CANCELLED,
    UNKNOWN,
    INVALID_ARGUMENT,
    DEADLINE_EXCEEDED,
    NOT_FOUND,
    ALREADY_EXISTS,
    PERMISSION_DENIED,
    RESOURCE_EXHAUSTED,
    FAILED_PRECONDITION,
    ABORTED,
    OUT_OF_RANGE,
    UNIMPLEMENTED,
    INTERNAL,
    UNAVAILABLE,
    DATA_LOSS,
    UNAUTHENTICATED
}

actual enum class Direction(internal val jsString : String) {
    ASCENDING("asc"),
    DESCENDING("desc");
}

actual enum class ChangeType(internal val jsString : String) {
    ADDED("added"),
    MODIFIED("modified"),
    REMOVED("removed");
}

inline fun <T, R> T.rethrow(function: T.() -> R): R = dev.gitlive.firebase.firestore.rethrow { function() }

inline fun <R> rethrow(function: () -> R): R {
    try {
        return function()
    } catch (e: Exception) {
        throw e
    } catch(e: dynamic) {
        throw errorToException(e)
    }
}

fun errorToException(e: dynamic) = (e?.code ?: e?.message ?: "")
    .toString()
    .lowercase()
    .let {
        when {
            "cancelled" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.CANCELLED)
            "invalid-argument" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.INVALID_ARGUMENT)
            "deadline-exceeded" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.DEADLINE_EXCEEDED)
            "not-found" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.NOT_FOUND)
            "already-exists" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.ALREADY_EXISTS)
            "permission-denied" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.PERMISSION_DENIED)
            "resource-exhausted" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.RESOURCE_EXHAUSTED)
            "failed-precondition" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.FAILED_PRECONDITION)
            "aborted" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.ABORTED)
            "out-of-range" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.OUT_OF_RANGE)
            "unimplemented" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.UNIMPLEMENTED)
            "internal" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.INTERNAL)
            "unavailable" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.UNAVAILABLE)
            "data-loss" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.DATA_LOSS)
            "unauthenticated" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.UNAUTHENTICATED)
            "unknown" in it -> FirebaseFirestoreException(e, FirestoreExceptionCode.UNKNOWN)
            else -> {
                println("Unknown error code in ${JSON.stringify(e)}")
                FirebaseFirestoreException(e, FirestoreExceptionCode.UNKNOWN)
            }
        }
    }

// from: https://discuss.kotlinlang.org/t/how-to-access-native-js-object-as-a-map-string-any/509/8
fun entriesOf(jsObject: dynamic): List<Pair<String, Any?>> =
    (js("Object.entries") as (dynamic) -> Array<Array<Any?>>)
        .invoke(jsObject)
        .map { entry -> entry[0] as String to entry[1] }

// from: https://discuss.kotlinlang.org/t/how-to-access-native-js-object-as-a-map-string-any/509/8
fun mapOf(jsObject: dynamic): Map<String, Any?> =
    entriesOf(jsObject).toMap()

private fun NativeDocumentReferenceType.get(source: Source) = when (source) {
    Source.DEFAULT -> getDoc(this)
    Source.CACHE -> getDocFromCache(this)
    Source.SERVER -> getDocFromServer(this)
}

private fun JsQuery.get(source: Source) = when (source) {
    Source.DEFAULT -> getDocs(this)
    Source.CACHE -> getDocsFromCache(this)
    Source.SERVER -> getDocsFromServer(this)
}