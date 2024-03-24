package net.ankio.qianji.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.ankio.auto.sdk.AutoAccounting
import net.ankio.common.constant.BillType
import net.ankio.common.model.AssetsModel
import net.ankio.common.model.BookModel
import net.ankio.common.model.CategoryModel
import net.ankio.qianji.api.Hooker
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * 用于将钱迹的数据同步给自动记账
 */
class SyncUtils(val context: Context,val classLoader: ClassLoader, private val hooker: Hooker) {


    private lateinit var bookManager :Any
    private lateinit var bookClazz :Class<*>
    private lateinit var proxyOnGetCategoryListClazz:Class<*>
    private lateinit var cateInitPresenterImplClazz:Class<*>
    private lateinit var category: Class<*>
    private lateinit var assetsClazz:Class<*>
    private lateinit var proxyOnGetAssetsClazz:Class<*>
    private lateinit var assetPreviewPresenterImplClazz:Class<*>
    fun init(){
        //初始化账本信息
        bookManager = XposedHelpers.callStaticMethod(classLoader.loadClass(hooker.clazz["BookManager"]),"getInstance")
        bookClazz = classLoader.loadClass("com.mutangtech.qianji.data.model.Book")
        //初始化分类信息
        cateInitPresenterImplClazz = classLoader.loadClass("com.mutangtech.qianji.bill.add.category.CateInitPresenterImpl")
       
        
        proxyOnGetCategoryListClazz = classLoader.loadClass(hooker.clazz["onGetCategoryList"])

        category = classLoader.loadClass("com.mutangtech.qianji.data.model.Category")


       assetPreviewPresenterImplClazz = classLoader.loadClass("com.mutangtech.qianji.asset.account.mvp.AssetPreviewPresenterImpl")

        assetsClazz = classLoader.loadClass("com.mutangtech.qianji.data.model.AssetAccount")

        proxyOnGetAssetsClazz = classLoader.loadClass(hooker.clazz["onGetAssetsFromApi"])
        
    }

    private suspend fun getCategoryList(bookId: Long): HashMap<String,Any> = suspendCoroutine { continuation ->

            val handler = InvocationHandler { _, method, args ->
                if (method.name == "onGetCategoryList") {
                    val list1 = args[0]
                    val list2 = args[1]
                    continuation.resume(hashMapOf("list1" to list1, "list2" to list2))
                }
                null
            }
            val proxyInstance = Proxy.newProxyInstance(classLoader, arrayOf(proxyOnGetCategoryListClazz), handler)
            val constructor = cateInitPresenterImplClazz.getDeclaredConstructor(proxyOnGetCategoryListClazz)
            val obj = constructor.newInstance(proxyInstance)
            val loadCategoryListMethod = cateInitPresenterImplClazz.getMethod("loadCategoryList", Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            loadCategoryListMethod.invoke(obj, bookId, false)

    }

    private suspend fun getAssetsList(): HashMap<String,Any> = suspendCoroutine { continuation ->

        val handler = InvocationHandler { _, method, args ->
            if (method.name == "onGetAssetsFromApi") {
                val z10 = args[0]
                val accounts = args[1]
                val hashMap = args[2]
             //   XposedBridge.log("账户信息:${Gson().toJson(accounts)},z10:${Gson().toJson(z10)},hashMap:${Gson().toJson(hashMap)}")
                continuation.resume(hashMapOf("accounts" to accounts))
            }else  if (method.name == "onGetAssetsFromDB") {
                val z10 = args[1]
                val accounts = args[0]
                val hashMap = args[2]
             //   XposedBridge.log("账户信息:${Gson().toJson(accounts)},z10:${Gson().toJson(z10)},hashMap:${Gson().toJson(hashMap)}")
                continuation.resume(hashMapOf("accounts" to accounts))
            }
            null
        }
        val proxyInstance = Proxy.newProxyInstance(classLoader, arrayOf(proxyOnGetAssetsClazz), handler)
        val constructor = assetPreviewPresenterImplClazz.getDeclaredConstructor(proxyOnGetAssetsClazz)
        val obj = constructor.newInstance(proxyInstance)
        val loadCategoryListMethod = assetPreviewPresenterImplClazz.getMethod("loadAssets", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        loadCategoryListMethod.invoke(obj,true, false)

    }



    suspend fun books() = withContext(Dispatchers.IO){
        val list = XposedHelpers.callMethod(bookManager,"getAllBooks",true,1) as List<*>
        val bookList = arrayListOf<BookModel>()

        /**
         * [
         *     {
         *         "bookId":-1,
         *         "cover":"http://res.qianjiapp.com/headerimages2/daniela-cuevas-t7YycgAoVSw-unsplash20_9.jpg!headerimages2",
         *         "createtimeInSec":0,
         *         "expired":0,
         *         "memberCount":1,
         *         "name":"日常账本",
         *         "sort":0,
         *         "type":-1,
         *         "updateTimeInSec":0,
         *         "userid":"",
         *         "visible":1
         *     }
         * ]
         */
       list.forEach { book ->
           if (bookClazz.isInstance(book)) {
               val bookModel = BookModel()
               // Get all fields of the Book class
               val fields = bookClazz.declaredFields
               for (field in fields) {
                   field.isAccessible = true
                   val value = field.get(book)
                   when(field.name){
                       "name" -> bookModel.name = value as String
                       "cover" -> bookModel.icon = value as String
                       "bookId" -> {
                           val hashMap = withContext(Dispatchers.Main){
                              getCategoryList(value as Long)
                           }
                           val arrayList = arrayListOf<CategoryModel>()
                           convertCategoryToModel(hashMap["list1"] as List<Any>,BillType.Expend).let {
                               arrayList.addAll(it)
                           }
                           convertCategoryToModel(hashMap["list2"] as List<Any>,BillType.Income).let {
                               arrayList.addAll(it)
                           }

                           bookModel.category = arrayList
                       }
                   }
               }



               //同步完成账本后，获取对应账本对应的分类
               // bookModel.category = categories()
               bookList.add(bookModel)
           }

       }


        XposedBridge.log("同步账本信息:${Gson().toJson(bookList)}")

       AutoAccounting.setBooks(context,Gson().toJson(bookList))
    }

    private fun convertCategoryToModel(list:List<Any>,type: BillType): ArrayList<CategoryModel> {
        val categories = arrayListOf<CategoryModel>()
        list.forEach {
            val category = it
            val model = CategoryModel(
                type = type.value
            )
            val fields = category::class.java.declaredFields
            for (field in fields) {
                field.isAccessible = true
                val value = field.get(category)
                /**
                 * [
                 *     {
                 *         "bookId": -1,
                 *         "editable": 1,
                 *         "icon": "http://res3.qianjiapp.com/cateic_gongzi.png",
                 *         "id": 20001,
                 *         "level": 1,
                 *         "name": "工资",
                 *         "parentId": -1,
                 *         "sort": 0,
                 *         "type": 1,
                 *         "userId": "u10001"
                 *     },
                 *     {
                 *         "bookId": -1,
                 *         "editable": 1,
                 *         "icon": "http://res3.qianjiapp.com/cateic_shenghuofei.png",
                 *         "id": 20002,
                 *         "level": 1,
                 *         "name": "生活费",
                 *         "parentId": -1,
                 *         "sort": 0,
                 *         "type": 1,
                 *         "userId": "u10001"
                 *     },
                 *     {
                 *         "bookId": -1,
                 *         "editable": 1,
                 *         "icon": "http://res3.qianjiapp.com/cateic_hongbao.png",
                 *         "id": 20003,
                 *         "level": 1,
                 *         "name": "收红包",
                 *         "parentId": -1,
                 *         "sort": 0,
                 *         "type": 1,
                 *         "userId": "u10001"
                 *     },
                 *     {
                 *         "bookId": -1,
                 *         "editable": 1,
                 *         "icon": "http://res3.qianjiapp.com/cateic_waikuai.png",
                 *         "id": 20004,
                 *         "level": 1,
                 *         "name": "外快",
                 *         "parentId": -1,
                 *         "sort": 0,
                 *         "type": 1,
                 *         "userId": "u10001"
                 *     },
                 *     {
                 *         "bookId": -1,
                 *         "editable": 1,
                 *         "icon": "http://res3.qianjiapp.com/cateic_gupiao.png",
                 *         "id": 20005,
                 *         "level": 1,
                 *         "name": "股票基金",
                 *         "parentId": -1,
                 *         "sort": 0,
                 *         "type": 1,
                 *         "userId": "u10001"
                 *     },
                 *     {
                 *         "bookId": -1,
                 *         "editable": 0,
                 *         "icon": "http://res3.qianjiapp.com/cateic_other.png",
                 *         "id": 20006,
                 *         "level": 1,
                 *         "name": "其它",
                 *         "parentId": -1,
                 *         "sort": 0,
                 *         "type": 1,
                 *         "userId": "u10001"
                 *     }
                 * ]
                 */
                when(field.name){
                    "name" -> model.name = value as String
                    "icon" -> model.icon = value as String
                    "id" -> model.id = (value as Long).toString()
                    "parentId" -> model.parent = (value as Long).toString()
                    "sort" -> model.sort = value  as Int
                    "subList" -> {
                        if(value!=null){
                            val subList = value as List<Any>
                        //    XposedBridge.log("子分类:${Gson().toJson(subList)}")
                            categories.addAll(convertCategoryToModel(subList,type))
                        }

                    }
                }
            }
            categories.add(model)
        }
        return categories
    }


    suspend fun assets(){
        val hashMap = withContext(Dispatchers.Main){
            getAssetsList()
        }
        val accounts = hashMap["accounts"] as List<*>
        val assets = arrayListOf<AssetsModel>()
        accounts.forEach {
            val asset = it!!
            val model = AssetsModel()
            XposedBridge.log("账户信息:${Gson().toJson(asset)}")
           val fields = asset::class.java.declaredFields
            var sync  = true
            for (field in fields) {
                field.isAccessible = true
                val value = field.get(asset)
                when(field.name){
                    "name" -> model.name = value as String
                    "icon" -> model.icon = value as String
                    "sort" -> model.sort = value  as Int
                    "type" ->{
                        // = 5 是债权人
                        sync  = value as Int == 5

                    }
                }
            }
            if(sync) assets.add(model)
        }
        XposedBridge.log("同步账户信息:${Gson().toJson(assets)}")
        AutoAccounting.setAssets(context,Gson().toJson(assets))
    }

    suspend fun billsFromQianJi(){

    }

    suspend fun billsFromAuto(){

    }
}