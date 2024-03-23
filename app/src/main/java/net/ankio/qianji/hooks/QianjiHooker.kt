package net.ankio.qianji.hooks

import android.content.Context
import android.widget.Toast
import com.google.gson.Gson
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ankio.dex.Dex
import net.ankio.dex.model.Clazz
import net.ankio.dex.model.ClazzField
import net.ankio.dex.model.ClazzMethod
import net.ankio.qianji.api.Hooker
import net.ankio.qianji.api.PartHooker


class QianjiHooker: Hooker(){
    override val packPageName: String = "com.mutangtech.qianji"
    override val appName: String = "钱迹"
    override var partHookers: MutableList<PartHooker> = arrayListOf(
        SidePartHooker(this),
        BookHooker(this)
    )

    override var clazz: HashMap<String, String> = hashMapOf(
        "BookManager" to "",
        "onGetCategoryList" to ""
    )

    private val clazzRule = mutableListOf(
       Clazz(
           name = "BookManager",
           nameRule = "^\\w{0,2}\\..+",
           type = "class",
           methods = listOf(
               ClazzMethod(
                   name = "isFakeDefaultBook",
                   returnType = "boolean"
               ),
               ClazzMethod(
                   name = "getAllBooks",
                   returnType = "java.util.List"
               )
           )
       ),
        Clazz(
            type = "interface",
            name = "onGetCategoryList",
            nameRule = "^\\w{0,2}\\..+",
            methods = listOf(
                ClazzMethod(
                    name = "onGetCategoryList",
                    returnType = "void",
                    parameters = listOf(
                        ClazzField(
                            type = "java.util.List"
                        ),
                        ClazzField(
                            type = "java.util.List"
                        ),
                        ClazzField(
                            type = "boolean"
                        )
                    )
                )
            )
        )
    )
    override fun hookLoadPackage(classLoader: ClassLoader?, context: Context?):Boolean {
        val code = hookUtils.getVersionCode()
        val adaptationVersion  = hookUtils.readData("adaptation").toIntOrNull() ?: 0
        if(adaptationVersion == code){
            runCatching {
                clazz = Gson().fromJson(hookUtils.readData("clazz"),HashMap::class.java) as HashMap<String, String>
            }.onFailure {
                hookUtils.writeData("adaptation","0")
                XposedBridge.log(it)
            }.onSuccess {
                return true
            }

        }
        hookUtils.toast("钱迹补丁开始适配中...")
        val total = clazz.size
        val hashMap = Dex.findClazz(context!!.packageResourcePath, classLoader!!, clazzRule)
        if(hashMap.size==total){
            hookUtils.writeData("adaptation",code.toString())
            clazz = hashMap
            hookUtils.writeData("clazz",Gson().toJson(clazz))
            XposedBridge.log("适配成功:${hashMap}")
            hookUtils.toast("适配成功")
            return  true
        }else{
            XposedBridge.log("适配失败:${hashMap}")
            hookUtils.toast("适配失败")
            return false
        }



        //BookManager被混淆过了，需要进行查找
        //TODO 如果用户已经登录，要求将账本和分类数据拉取到本地

        //TODO 复制钱迹数据库到缓存单独处理

    }

}