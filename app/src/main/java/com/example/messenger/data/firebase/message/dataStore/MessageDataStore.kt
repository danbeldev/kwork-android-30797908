package com.example.messenger.data.firebase.message.dataStore

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.messenger.data.firebase.message.model.Image
import com.example.messenger.data.firebase.message.model.Message
import com.example.messenger.utils.FirebaseConstants.CHILD_FROM
import com.example.messenger.utils.FirebaseConstants.CHILD_TEXT
import com.example.messenger.utils.FirebaseConstants.CHILD_TIME_STAMP
import com.example.messenger.utils.FirebaseConstants.CHILD_TYPE
import com.example.messenger.utils.FirebaseConstants.FOLDER_IMAGES
import com.example.messenger.utils.FirebaseConstants.FOLDER_MESSAGES
import com.example.messenger.utils.FirebaseConstants.NODE_IMAGES
import com.example.messenger.utils.FirebaseConstants.NODE_MESSAGES
import com.example.messenger.utils.extensions.asByteArray
import com.example.messenger.utils.extensions.asTimeFormat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.UploadTask
import java.util.UUID
import javax.inject.Inject

class MessageDataStore @Inject constructor(
    private val database: FirebaseDatabase,
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage
) {
     fun sendMessage(
         message: Message,
         receivingUserId:String,
         images:List<Uri> = emptyList(),
         bitmapImages:List<Bitmap> = emptyList(),
         onSuccessListener:() -> Unit = {},
         onFailureListener:() -> Unit = {},
         onProgress: (progress:Long,totalKb:Long,transferredKb:Long) -> Unit
    )  {
         var progress = 0L
         var totalKb = 0L
         var transferredKb = 0L
         var bytesTransferred = 0L
         var totalByteCount = 0L

        val refDialogUser = "/$NODE_MESSAGES/${auth.currentUser?.uid ?: return}/$receivingUserId"
        val refDialogReceivingUser = "/$NODE_MESSAGES/$receivingUserId/${auth.currentUser?.uid}"
        val messageKey = database.reference.child(refDialogUser).push().key

        val mapMessage = hashMapOf<String,Any>()
        mapMessage[CHILD_FROM] = auth.currentUser?.uid ?: return
        mapMessage[CHILD_TYPE] = message.type
        mapMessage[CHILD_TEXT] = message.text
        mapMessage[CHILD_TIME_STAMP] = ServerValue.TIMESTAMP

        val mapDialog = hashMapOf<String,Any>()
        mapDialog["$refDialogUser/$messageKey"] = mapMessage
        mapDialog["$refDialogReceivingUser/$messageKey"] = mapMessage

        database.reference.updateChildren(mapDialog)
            .addOnSuccessListener { onSuccessListener() }
            .addOnFailureListener { onFailureListener() }

         val urlPatch = "/$refDialogUser/${messageKey ?: return}/$NODE_IMAGES/"
         val urlReceivingUserPatch = "/$refDialogReceivingUser/${messageKey}/$NODE_IMAGES/"

         bitmapImages.forEach { image ->

             var bitmapTotalKb = 0L
             var bitmapTransferredKb = 0L
             var bitmapBytesTransferred = 0L
             var bitmapTotalByteCount = 0L

             pushMessageImage(
                 image = image,
                 onSuccessListener = { url ->
                     val urlKey = UUID.randomUUID().toString()

                     val urlReceivingUserKey = UUID.randomUUID().toString()

                     saveImageDatabase(
                         urlImage = url,
                         urlPatch = urlPatch,
                         urlKey = urlKey,
                         urlReceivingUserPatch = urlReceivingUserPatch,
                         urlReceivingUserKey = urlReceivingUserKey,
                         onSuccessListener = onSuccessListener,
                         onFailureListener = onFailureListener
                     )
                 },
                 onFailureListener = { onFailureListener() },
                 onProgress = {
                     bitmapBytesTransferred = it.bytesTransferred
                     bitmapTotalByteCount = it.totalByteCount

                     bitmapTotalKb = (it.totalByteCount / 1024)
                     bitmapTransferredKb = it.bytesTransferred / 1024

                     onProgress(progress, totalKb, transferredKb)
                 }
             )

             bytesTransferred =+ bitmapBytesTransferred
             totalKb =+ bitmapTotalKb
             totalByteCount =+ bitmapTotalByteCount
             transferredKb =+ bitmapTransferredKb
             progress = (100*bytesTransferred) / totalByteCount
         }

        images.forEach { image ->
            pushMessageImage(
                image = image,
                onSuccessListener = { url ->
                    val urlKey = UUID.randomUUID().toString()

                    val urlReceivingUserKey = UUID.randomUUID().toString()

                    saveImageDatabase(
                        urlImage = url,
                        urlPatch = urlPatch,
                        urlKey = urlKey,
                        urlReceivingUserPatch = urlReceivingUserPatch,
                        urlReceivingUserKey = urlReceivingUserKey,
                        onSuccessListener = onSuccessListener,
                        onFailureListener = onFailureListener
                    )
                },
                onFailureListener = { onFailureListener() },
                onProgress = {
                    bytesTransferred = it.bytesTransferred
                    totalByteCount = it.totalByteCount

                    totalKb = (it.totalByteCount / 1024)
                    transferredKb = it.bytesTransferred / 1024

                    progress = (100*bytesTransferred) / totalByteCount

                    onProgress(progress, totalKb, transferredKb)
                }
            )
        }
    }

    fun getMessages(
        receivingUserId:String,
        onSuccessListener:(List<Message>) -> Unit = {},
        onFailureListener:(Exception) -> Unit = {}
    ){
        try {
            database.reference.child(NODE_MESSAGES)
                .child(auth.currentUser?.uid ?: return)
                .child(receivingUserId)
                .addValueEventListener(object: ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val messages = ArrayList<Message>()

                        for (i in snapshot.children){
                            messages.add(
                                Message(
                                    text = i.child(CHILD_TEXT).value.toString(),
                                    type = i.child(CHILD_TYPE).value.toString(),
                                    from = i.child(CHILD_FROM).value.toString(),
                                    timeStamp = i.child(CHILD_TIME_STAMP).value.toString().asTimeFormat(),
                                    images = i.child(NODE_IMAGES).children.map {
                                        Image(
                                            url = it.value.toString()
                                        )
                                    }
                                )
                            )
                        }
                        onSuccessListener(messages)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        onFailureListener(error.toException())
                    }
                })
        }catch (e:Exception){
            onFailureListener(e)
        }
    }

    fun saveImageDatabase(
        urlImage:String,
        urlPatch:String,
        urlKey:String,
        urlReceivingUserPatch:String,
        urlReceivingUserKey:String,
        onSuccessListener:() -> Unit,
        onFailureListener:() -> Unit,
    ){
        database.reference.child(urlPatch).child(urlKey)
            .setValue(urlImage)
            .addOnSuccessListener {
                database.reference
                    .child(urlReceivingUserPatch)
                    .child(urlReceivingUserKey)
                    .setValue(urlImage)
                    .addOnSuccessListener { onSuccessListener() }
                    .addOnFailureListener { onFailureListener() }
            }
            .addOnFailureListener { onFailureListener() }
    }

    fun pushMessageImage(
        image:Bitmap,
        onProgress:(UploadTask.TaskSnapshot) -> Unit = {},
        onSuccessListener:(url:String) -> Unit = {},
        onFailureListener:(Exception) -> Unit = {}
    ){
        try {
            val patch = storage.reference.child(FOLDER_MESSAGES).child(FOLDER_IMAGES)
                .child(auth.currentUser?.uid ?: return).child(UUID.randomUUID().toString())

            patch.putBytes(image.asByteArray())
                .addOnProgressListener {
                    onProgress(it)
                }
                .addOnCompleteListener { it ->
                    if (it.isSuccessful){
                        patch.downloadUrl.addOnCompleteListener {
                            if (it.isSuccessful){
                                onSuccessListener(it.result.toString())
                            }else {
                                onFailureListener(it.exception!!)
                            }
                        }
                    }else {
                        onFailureListener(it.exception!!)
                    }
                }
        }catch (e:Exception){
            onFailureListener(e)
        }
    }

    fun pushMessageImage(
        image:Uri,
        onProgress:(UploadTask.TaskSnapshot) -> Unit = {},
        onSuccessListener:(url:String) -> Unit = {},
        onFailureListener:(Exception) -> Unit = {}
    ){
        try {
            val patch = storage.reference.child(FOLDER_MESSAGES).child(FOLDER_IMAGES)
                .child(auth.currentUser?.uid ?: return).child(UUID.randomUUID().toString())

            patch.putFile(image)
                .addOnFailureListener(onFailureListener)
                .addOnProgressListener {
                    onProgress(it)
                }
                .addOnCompleteListener { it ->
                    if (it.isSuccessful){
                        patch.downloadUrl.addOnCompleteListener {
                            if (it.isSuccessful){
                                onSuccessListener(it.result.toString())
                            }else {
                                onFailureListener(it.exception!!)
                            }
                        }
                    }else {
                        onFailureListener(it.exception!!)
                    }
                }

        }catch (e:Exception){ onFailureListener(e) }
    }
}