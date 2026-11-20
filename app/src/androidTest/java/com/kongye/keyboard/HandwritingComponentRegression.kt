package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object HandwritingComponentRegression {
    fun run(i:Instrumentation) {
        check(android.os.Build.MODEL.startsWith("sdk_"))
        val ctx=i.targetContext
        val target=ResourcePacks.file(ctx,"handwriting")
        val original=target.takeIf {it.exists()}?.readBytes()
        val marker=File(target.parentFile,target.name+".sha256")
        val oldMarker=marker.takeIf {it.exists()}?.readBytes()
        fun ui(block:()->Unit)=i.runOnMainSync(block)
        fun line(vararg p:Float)=(p.indices step 2).map {floatArrayOf(p[it],p[it+1])}
        fun place(strokes:List<List<FloatArray>>,x:Float,y:Float)=strokes.map {s->s.map {floatArrayOf(it[0]+x,it[1]+y)}}
        val fire=listOf(line(34f,40f,39f,65f),line(84f,34f,69f,62f),line(62f,18f,55f,61f,45f,84f,23f,112f),line(53f,76f,72f,91f,98f,113f))
        val earth=listOf(line(35f,60f,100f,60f),line(66f,20f,66f,120f),line(15f,120f,119f,120f))
        // Trace the four groups from the supplied example, including overlap
        // across the guide lines and a one-point dot.
        val photographed=listOf(
            line(295f,267f,322f,353f),line(438f,250f,411f,325f),line(400f,226f,365f,316f,322f,395f,247f,487f),line(350f,331f,411f,360f,449f,404f),
            line(603f,231f,578f,310f,552f,365f),line(508f,302f,554f,322f,588f,321f),line(686f,268f,631f,311f),line(584f,363f,633f,361f,652f,372f),
            line(281f,487f,319f,522f,353f,528f),line(484f,458f,439f,518f),line(399f,439f,400f,494f,372f,568f,341f,627f,306f,682f),line(375f,575f,429f,605f,491f,623f),
            line(478f,530f,535f,538f,580f,530f),line(615f,409f,622f,464f,600f,538f,577f,584f,560f,643f),line(675f,457f),line(615f,536f,691f,552f,756f,580f))
        try {
            // Deliberately remove a previously downloaded pack: this exercises
            // the first-use APK asset, not a model silently installed by QA.
            target.delete();marker.delete()
            check(ResourcePacks.bundled(ctx,"handwriting"))
            check(!ResourcePacks.ready(ctx,"handwriting"))
            ui {OpenHandwriting.release()}
            val loaded=CountDownLatch(1)
            ui {OpenHandwriting.prepare(ctx){loaded.countDown()}}
            check(loaded.await(20,TimeUnit.SECONDS) && OpenHandwriting.ready) {OpenHandwriting.failure.orEmpty()}
            val latest=listOf(
                line(285f,1431f,284f,1507f),line(365f,1420f,357f,1470f,340f,1500f),
                line(324f,1414f,329f,1465f,311f,1520f,280f,1580f,258f,1615f),line(324f,1528f,331f,1563f,347f,1590f),
                line(401f,1425f,419f,1464f),line(491f,1423f,473f,1467f,453f,1491f),
                line(454f,1394f,456f,1440f,437f,1489f,411f,1524f,392f,1548f),line(446f,1483f,474f,1527f,495f,1550f),
                line(241f,1667f,243f,1699f,255f,1717f),line(344f,1661f,335f,1690f,313f,1715f),
                line(300f,1625f,307f,1660f,293f,1712f,272f,1748f,257f,1773f),line(293f,1729f,328f,1766f,359f,1790f),
                line(399f,1624f,416f,1650f),line(520f,1624f,501f,1663f,479f,1686f),
                line(452f,1578f,459f,1636f,438f,1685f,412f,1723f),line(451f,1693f,490f,1720f,531f,1738f,568f,1747f))
            fun dense(strokes:List<List<FloatArray>>)=strokes.map {stroke->
                if(stroke.size<2)stroke else stroke.zipWithNext().flatMap {(a,b)->
                    (0..7).map {n->floatArrayOf(a[0]+(b[0]-a[0])*n/7,a[1]+(b[1]-a[1])*n/7)}
                }
            }
            val cases=listOf("燚" to (place(fire,0f,0f)+place(fire,140f,0f)+place(fire,0f,140f)+place(fire,140f,140f)),
                "燚" to photographed,"燚" to dense(latest),"垚" to (place(earth,70f,0f)+place(earth,0f,140f)+place(earth,140f,140f)))
            for((word,strokes) in cases) {
                val done=CountDownLatch(1);var primary=emptyList<String>();var extra=emptyList<String>()
                val start=SystemClock.elapsedRealtime()
                ui {check(OpenHandwriting.recognize(strokes,{primary=it},{extra=it;done.countDown()}))}
                check(done.await(30,TimeUnit.SECONDS)) {"Component recognition timeout"}
                i.sendStatus(0,Bundle().apply {putString("stream","$word: primary=${primary.take(5)}, components=$extra, ${SystemClock.elapsedRealtime()-start} ms\n")})
                check(word in primary+extra) {"General component recognition missed $word"}
            }
            i.sendStatus(0,Bundle().apply {putString("stream","HANDWRITING COMPONENTS PASSED: bundled model without download; regular, overlapping and latest dense four-fire trajectories; three-earth structure; real neural component predictions and general IDS data\n")})
        } finally {
            ui {OpenHandwriting.release()}
            if(original==null)target.delete()else target.writeBytes(original)
            if(oldMarker==null)marker.delete()else marker.writeBytes(oldMarker)
        }
    }
}
