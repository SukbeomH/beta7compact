package dev.sukbeomh.beta7compact.recipe

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES30.*
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One EGL context per job, confined to its creating thread. RGBA8 encoded values, no sRGB decode. */
class GpuEngine(private val lut: Lut) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val textures = IntArray(3)
    private val frame = IntArray(1)
    private var readback: ByteBuffer? = null
    init {
        try {
            check(EGL14.eglInitialize(display, IntArray(2),0,IntArray(2),0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,0x40,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
                EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
            context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
            surface = EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
            check(EGL14.eglMakeCurrent(display,surface,surface,context))
            fun shader(type: Int, source: String): Int {
                val id = glCreateShader(type); glShaderSource(id,source); glCompileShader(id)
                val ok = IntArray(1); glGetShaderiv(id,GL_COMPILE_STATUS,ok,0)
                if (ok[0] == 0) { glDeleteShader(id); error("GPU 셰이더를 초기화할 수 없습니다") }; return id
            }
            val vertex = shader(GL_VERTEX_SHADER,VERTEX)
            val fragment = try { shader(GL_FRAGMENT_SHADER,FRAGMENT) } catch (e: Exception) { glDeleteShader(vertex); throw e }
            program = glCreateProgram(); glAttachShader(program,vertex); glAttachShader(program,fragment); glLinkProgram(program)
            glDeleteShader(vertex); glDeleteShader(fragment)
            val ok = IntArray(1); glGetProgramiv(program,GL_LINK_STATUS,ok,0); check(ok[0] != 0)
            glGenTextures(3,textures,0); glGenFramebuffers(1,frame,0)
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_3D,textures[1])
            val data = ByteBuffer.allocateDirect(lut.size*lut.size*lut.size*4*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in lut.values.indices step 3) { data.put(lut.values[i]);data.put(lut.values[i+1]);data.put(lut.values[i+2]);data.put(1f) }; data.position(0)
            glTexImage3D(GL_TEXTURE_3D,0,GL_RGBA32F,lut.size,lut.size,lut.size,0,GL_RGBA,GL_FLOAT,data)
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_NEAREST)
            glUseProgram(program); glUniform1i(glGetUniformLocation(program,"source"),0);glUniform1i(glGetUniformLocation(program,"look"),1)
            glUniform1i(glGetUniformLocation(program,"size"),lut.size)
            glUniform3fv(glGetUniformLocation(program,"domainMin"),1,lut.domainMin,0)
            glUniform3fv(glGetUniformLocation(program,"domainMax"),1,lut.domainMax,0)
            checkGl()
        } catch (t: Throwable) { close(); throw t }
    }
    fun render(bitmap: Bitmap, recipe: Recipe, x: Int = 0, y: Int = 0,
               fullWidth: Int = bitmap.width, fullHeight: Int = bitmap.height,
               scaleX: Float = 1f, scaleY: Float = 1f): Bitmap {
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,textures[0])
        GLUtils.texImage2D(GL_TEXTURE_2D,0,bitmap,0)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST)
        glBindTexture(GL_TEXTURE_2D,textures[2]);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,bitmap.width,bitmap.height,0,GL_RGBA,GL_UNSIGNED_BYTE,null)
        glBindFramebuffer(GL_FRAMEBUFFER,frame[0]);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[2],0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
        glBindTexture(GL_TEXTURE_2D,textures[0]);glViewport(0,0,bitmap.width,bitmap.height)
        fun f(name: String,v: Float) = glUniform1f(glGetUniformLocation(program,name),v)
        f("strength",recipe.strength);f("temperature",recipe.wb.temperature);f("tint",recipe.wb.tint);f("ev",recipe.ev)
        f("grainSize",recipe.grain.size);f("grainAmount",recipe.grain.amount);f("vignette",recipe.vignette)
        val edits=recipe.edits
        f("highlights",edits.highlights);f("shadows",edits.shadows);f("contrast",edits.contrast)
        f("saturation",edits.saturation);f("vibrance",edits.vibrance)
        val curves=edits.curves.list()
        glUniform3fv(glGetUniformLocation(program,"points[0]"),64,curves.flatMap { it.packed().toList() }.toFloatArray(),0)
        glUniform4iv(glGetUniformLocation(program,"counts"),1,curves.map { it.points.size }.toIntArray(),0)
        glUniform3fv(glGetUniformLocation(program,"bands[0]"),8,edits.hsl.flatMap { listOf(it.hue,it.saturation,it.luminance) }.toFloatArray(),0)
        val t=edits.split
        glUniform4f(glGetUniformLocation(program,"split"),t.shadowHue,t.shadowSaturation,t.highlightHue,t.highlightSaturation)
        f("balance",t.balance)
        glUniform1i(glGetUniformLocation(program,"colorVersion"),recipe.colorVersion)
        glUniform1i(glGetUniformLocation(program,"seed"),recipe.grain.seed)
        glUniform1i(glGetUniformLocation(program,"transform"),if (recipe.inputTransform == InputTransform.NONE) 0 else 1)
        glUniform2f(glGetUniformLocation(program,"origin"),x.toFloat(),y.toFloat())
        glUniform2f(glGetUniformLocation(program,"fullSize"),fullWidth.toFloat(),fullHeight.toFloat())
        glUniform2f(glGetUniformLocation(program,"pixelScale"),scaleX,scaleY)
        glDisable(GL_BLEND);glDisable(GL_DITHER);glDrawArrays(GL_TRIANGLES,0,3)
        val needed = bitmap.width*bitmap.height*4
        val bytes = readback?.takeIf { it.capacity() >= needed }
            ?: ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder()).also { readback = it }
        bytes.clear(); bytes.limit(needed)
        glReadPixels(0,0,bitmap.width,bitmap.height,GL_RGBA,GL_UNSIGNED_BYTE,bytes);checkGl();bytes.position(0)
        return Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(bytes) }
    }
    private fun checkGl() { check(glGetError() == GL_NO_ERROR) { "GPU 처리를 완료할 수 없습니다" } }
    override fun close() {
        if (context != EGL14.EGL_NO_CONTEXT) {
            glDeleteTextures(3,textures,0);glDeleteFramebuffers(1,frame,0);if (program != 0) glDeleteProgram(program)
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface)
            EGL14.eglDestroyContext(display,context)
            context = EGL14.EGL_NO_CONTEXT
        }
        EGL14.eglTerminate(display)
    }
    companion object {
        private const val VERTEX = """#version 300 es
        void main() { vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); gl_Position=vec4(p*2.0-1.0,0.0,1.0); }
        """
        private const val FRAGMENT = """#version 300 es
        precision highp float;
        precision highp int;
        precision highp sampler3D;
        uniform sampler2D source;
        uniform sampler3D look;
        uniform int size, seed, transform, colorVersion;
        uniform vec3 points[64],bands[8];
        uniform ivec4 counts;
        uniform float highlights,shadows,contrast,saturation,vibrance,balance;
        uniform vec4 split;
        uniform float strength,temperature,tint,ev,grainSize,grainAmount,vignette;
        uniform vec2 origin,fullSize,pixelScale;
        uniform vec3 domainMin,domainMax;
        out vec4 result;
        float lin(float v) { return v<=0.04045?v/12.92:pow((v+0.055)/1.055,2.4); }
        float enc(float v) { return v<=0.0031308?12.92*v:1.055*pow(v,1.0/2.4)-0.055; }
        float slog(float v) { return v>=171.2102946929/1023.0?pow(10.0,(v*1023.0-420.0)/261.5)*0.19-0.01:(v*1023.0-95.0)*0.01125/(171.2102946929-95.0); }
        vec3 sampleLut(vec3 c) {
            vec3 p=clamp((c-domainMin)/(domainMax-domainMin),0.0,1.0)*float(size-1); ivec3 lo=min(ivec3(floor(p)),ivec3(size-2)); vec3 t=p-vec3(lo);
            vec3 sum=vec3(0);
            for(int b=0;b<2;b++) for(int g=0;g<2;g++) for(int r=0;r<2;r++) {
                float w=(r==0?1.0-t.x:t.x)*(g==0?1.0-t.y:t.y)*(b==0?1.0-t.z:t.z);
                sum+=texelFetch(look,lo+ivec3(r,g,b),0).rgb*w;
            } return sum;
        }
        float noise(ivec2 p) {
            uint h=uint(p.x)*374761393u+uint(p.y)*668265263u+uint(seed)*69069u;
            h=(h^(h>>13))*1274126177u; h=h^(h>>16);
            return float(h&65535u)/65535.0-0.5;
        }
        float curve(float v,int channel) {
            int n=counts[channel]; int i=0;
            for(int j=0;j<15;j++) { if(j<n-1 && points[channel*16+j].x<=v) i=j; }
            vec3 a=points[channel*16+i],b=points[channel*16+i+1];
            float h=b.x-a.x,t=clamp((v-a.x)/h,0.0,1.0);
            return clamp((2.0*t*t*t-3.0*t*t+1.0)*a.y+(t*t*t-2.0*t*t+t)*h*a.z+
                (-2.0*t*t*t+3.0*t*t)*b.y+(t*t*t-t*t)*h*b.z,min(a.y,b.y),max(a.y,b.y));
        }
        vec3 toHsl(vec3 c) {
            float hi=max(c.r,max(c.g,c.b)),lo=min(c.r,min(c.g,c.b));
            float d=hi-lo,l=(hi+lo)*0.5;
            if(d<0.0000001) return vec3(0,0,l);
            float h=hi==c.r?(c.g-c.b)/d:(hi==c.g?(c.b-c.r)/d+2.0:(c.r-c.g)/d+4.0);
            return vec3(mod(h*60.0+360.0,360.0),d/(1.0-abs(2.0*l-1.0)),l);
        }
        vec3 toRgb(float hue,float s,float l) {
            float h=mod(hue+720.0,360.0)/60.0,c=(1.0-abs(2.0*l-1.0))*s;
            float x=c*(1.0-abs(mod(h,2.0)-1.0)),m=l-c/2.0;
            vec3 v=h<1.0?vec3(c,x,0):h<2.0?vec3(x,c,0):h<3.0?vec3(0,c,x):h<4.0?vec3(0,x,c):h<5.0?vec3(x,0,c):vec3(c,0,x);
            return v+m;
        }
        vec3 edit(vec3 c) {
            float l=dot(c,vec3(0.2126,0.7152,0.0722));
            float shift=(shadows/100.0*(1.0-l)*(1.0-l)+highlights/100.0*l*l)*0.25;
            c=clamp((c+shift-0.5)*exp2(contrast/100.0)+0.5,0.0,1.0);
            c=vec3(curve(curve(c.r,0),1),curve(curve(c.g,0),2),curve(curve(c.b,0),3));
            vec3 hsl=toHsl(c);
            float centers[8]=float[8](0.0,30.0,60.0,120.0,180.0,240.0,270.0,300.0);
            int i=0;for(int j=0;j<8;j++) if(centers[j]<=hsl.x) i=j;
            int next=(i+1)%8;float end=next==0?360.0:centers[next];
            float t=(hsl.x-centers[i])/(end-centers[i]);t=t*t*(3.0-2.0*t);
            vec3 band=mix(bands[i],bands[next],t)/100.0;
            float h=hsl.x+band.x*30.0;
            float s=clamp(hsl.y*(1.0+saturation/100.0+vibrance/100.0*(1.0-hsl.y))*(1.0+band.y),0.0,1.0);
            float light=clamp(hsl.z+band.z*0.25,0.0,1.0);
            c=toRgb(h,s,light);
            t=clamp((light-0.5)*2.0-balance/100.0,-1.0,1.0)*0.5+0.5;
            return clamp(c+(toRgb(split.x,1.0,0.5)-0.5)*(1.0-t)*split.y/500.0+
                (toRgb(split.z,1.0,0.5)-0.5)*t*split.w/500.0,0.0,1.0);
        }
        void main() {
            vec4 src=texelFetch(source,ivec2(gl_FragCoord.xy),0);
            vec3 c=src.a>0.0?src.rgb/src.a:vec3(0);
            if(transform==1) {
                vec3 v=vec3(slog(c.r),slog(c.g),slog(c.b));
                vec3 m=vec3(dot(v,vec3(1.6269474,-0.5401385,-0.0868089)),dot(v,vec3(-0.1785155,1.4179409,-0.2394254)),dot(v,vec3(-0.0444361,-0.19591997,1.2403561)));
                c=clamp(vec3(enc(m.r),enc(m.g),enc(m.b)),0.0,1.0);
            }
            if(colorVersion==1) c=clamp(mix(c,sampleLut(c),strength),0.0,1.0);
            c=vec3(lin(c.r),lin(c.g),lin(c.b))*exp2(vec3(temperature*0.5,-tint*0.5,-temperature*0.5)+ev);
            c=clamp(vec3(enc(c.r),enc(c.g),enc(c.b)),0.0,1.0);
            c=edit(c);
            if(colorVersion>=2) c=clamp(mix(c,sampleLut(c),strength),0.0,1.0);
            vec2 pos=origin+(gl_FragCoord.xy-0.5)*pixelScale;
            float l=dot(c,vec3(0.2126,0.7152,0.0722));
            float grain=noise(ivec2(floor(pos/grainSize)))*grainAmount*0.2*(0.25+0.75*sqrt(l*(1.0-l)));
            vec2 d=(pos+0.5)/fullSize*2.0-1.0;
            c=clamp((c+grain)*(1.0-vignette*clamp(dot(d,d)*0.5,0.0,1.0)),0.0,1.0);
            result=vec4(c*src.a,src.a);
        }
        """
    }
}
