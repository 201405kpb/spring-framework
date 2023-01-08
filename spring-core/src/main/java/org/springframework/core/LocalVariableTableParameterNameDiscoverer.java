/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.core;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.asm.*;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of {@link ParameterNameDiscoverer} that uses the LocalVariableTable
 * information in the method attributes to discover parameter names. Returns
 * {@code null} if the class file was compiled without debug information.
 * <p>{@link ParameterNameDiscoverer}的实现,该方法使用方法属性中的LocalVariableTable信息
 * 来发现参数名.如果类文件是在没有调试信息的情况下编译的,则返回{@code null}</p>
 *
 * <p>Uses ObjectWeb's ASM library for analyzing class files. Each discoverer instance
 * caches the ASM discovered information for each introspected Class, in a thread-safe
 * manner. It is recommended to reuse ParameterNameDiscoverer instances as far as possible.
 *
 * <p>使用ObjectWeb的ASM库分析类文件。每个发现者实例以线程安全的方式为每个自省类缓存ASM
 * 发现的信息。建议尽可能重用ParameterNameDiscover实例</p>
 *
 * <p>This class is deprecated in the 6.0 generation and scheduled for removal in 6.1
 * since it is effectively superseded by {@link StandardReflectionParameterNameDiscoverer}.
 * For the time being, this discoverer logs a warning every time it actually inspects a
 * class file which is particularly useful for identifying remaining gaps in usage of
 * the standard "-parameters" compiler flag, and also unintended over-inspection of
 * e.g. JDK core library classes (which are not compiled with the "-parameters" flag).
 *
 * @author Adrian Colyer
 * @author Costin Leau
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Sam Brannen
 * @see StandardReflectionParameterNameDiscoverer
 * @see DefaultParameterNameDiscoverer
 * @since 2.0
 * @deprecated as of 6.0.1, in favor of {@link StandardReflectionParameterNameDiscoverer}
 * (with the "-parameters" compiler flag)
 */
@Deprecated(since = "6.0.1", forRemoval = true)
public class LocalVariableTableParameterNameDiscoverer implements ParameterNameDiscoverer {

	/**
	 * 日志类
	 */
	private static final Log logger = LogFactory.getLog(LocalVariableTableParameterNameDiscoverer.class);

	// marker object for classes that do not have any debug info
	// 没有任何调试信息的类的标记对象
	private static final Map<Executable, String[]> NO_DEBUG_INFO_MAP = Collections.emptyMap();

	// the cache uses a nested index (value is a map) to keep the top level cache relatively small in size
	// 方法声明类 - (Constructor/Method对象-参数名数组缓存) 线程安全缓存Map
	// 缓存使用嵌套索引(值是一个映射）来使得顶级缓存相对较小
	private final Map<Class<?>, Map<Executable, String[]>> parameterNamesCache = new ConcurrentHashMap<>(32);


	@Override
	@Nullable
	public String[] getParameterNames(Method method) {
		//获取提供的桥接方法的原始方法
		Method originalMethod = BridgeMethodResolver.findBridgedMethod(method);
		//获取给定Constructor/Method的参数名数组
		return doGetParameterNames(originalMethod);
	}

	@Override
	@Nullable
	public String[] getParameterNames(Constructor<?> ctor) {
		return doGetParameterNames(ctor);
	}

	/**
	 * 获取给定Constructor/Method的参数名数组
	 *
	 * @param executable Constructor/Method对象
	 * @return 给定Constructor/Method的参数名数组
	 */
	@Nullable
	private String[] doGetParameterNames(Executable executable) {
		//获取executable的声明类
		Class<?> declaringClass = executable.getDeclaringClass();
		//获取declaringClass对应的Map<Executable,String[]>对象，如果没有，通过inspectClass方法构建一个
		Map<Executable, String[]> map = this.parameterNamesCache.computeIfAbsent(declaringClass, this::inspectClass);
		//如果map不是没有任何调试信息的类的标记对象，就返回在map中对应executable的参数名数组；否则返回null
		return (map != NO_DEBUG_INFO_MAP ? map.get(executable) : null);
	}

	/**
	 * Inspects the target class.
	 * <p>检查目标类</p>
	 * <p>Exceptions will be logged, and a marker map returned to indicate the
	 * lack of debug information.
	 * <p>将记录异常，并返回标记映射以指示调试信息</p>
	 */
	private Map<Executable, String[]> inspectClass(Class<?> clazz) {
		//获取clazz的class文件流
		InputStream is = clazz.getResourceAsStream(ClassUtils.getClassFileName(clazz));
		//如果文件流为null
		if (is == null) {
			// We couldn't load the class file, which is not fatal as it
			// simply means this method of discovering parameter names won't work.
			// 我们无法加载类文件，它不是致命的，因为它仅仅意味着发现参数名称的这种方法将行不通。
			//如果是debug模式
			if (logger.isDebugEnabled()) {
				//log：没有找到类[clazz]的'.class'文件-无法确定构造函数/方法的参数名
				logger.debug("Cannot find '.class' file for class [" + clazz +
						"] - unable to determine constructor/method parameter names");
			}
			//返回没有任何调试信息的类的标记对象
			return NO_DEBUG_INFO_MAP;
		}
		try {
			//ClassVisitor:访问Java类的访问者
			//ClassReader:用来使{@link ClassVisitor}的解析器访问Java虚拟机规范(JVMS)中的定义的ClassFile结构。此类
			// 解析ClassFile内容，并为遇到的每个字段，方法和字节码调用给定{@link ClassVisitor}的适当访问方法
			ClassReader classReader = new ClassReader(is);
			//初始化一个并发哈希映射，初始化容量为32，用于存储Method/Constructor对象-参数名数组
			Map<Executable, String[]> map = new ConcurrentHashMap<>(32);
			//ParameterNameDiscoveringVisitor:帮助类，它检查所有方法和构造函数，然后尝试查找给定的Executable的参数名
			//ClassReader.accept:使用给定的方法者访问传递给此ClassReader的构造函数的JVMS ClassFile结构。
			classReader.accept(new ParameterNameDiscoveringVisitor(clazz, map), 0);
			//返回用于存储Method/Constructor对象-参数名数组映射
			return map;
		} catch (IOException ex) {
			//捕捉IO异常
			//如果日志级别为DEBUG级别
			if (logger.isDebugEnabled()) {
				//日志描述：读取类[clazz]的'.class'文件时引发异常-无法确定构造函数/方法参数名称
				logger.debug("Exception thrown while reading '.class' file for class [" + clazz +
						"] - unable to determine constructor/method parameter names", ex);
			}
		} catch (IllegalArgumentException ex) {
			//捕捉非法参数异常
			//如果日志级别为DEBUG级别
			if (logger.isDebugEnabled()) {
				//日志描述：ASM ClassReader无法解析类文件[clazz]，可能是由于尚不支持Java类文件版本
				// -无法确定构造函数/方法参数名称
				logger.debug("ASM ClassReader failed to parse class file [" + clazz +
						"], probably due to a new Java class file version that isn't supported yet " +
						"- unable to determine constructor/method parameter names", ex);
			}
		} finally {
			try {
				//关闭类流
				is.close();
			} catch (IOException ex) {
				// ignore -- 忽略关闭流是的所有IO异常
			}
		}
		//返回没有任何调试信息的类的标记对象
		return NO_DEBUG_INFO_MAP;
	}


	/**
	 * Helper class that inspects all methods and constructors and then
	 * attempts to find the parameter names for the given {@link Executable}.
	 * <p>帮助类，它检查所有方法和构造函数，然后尝试查找给定的{@link Executable}的
	 * 参数名</p>
	 */
	private static class ParameterNameDiscoveringVisitor extends ClassVisitor {

		/**
		 * 静态类初始化
		 */
		private static final String STATIC_CLASS_INIT = "<clinit>";

		/**
		 * 方法声明类
		 */
		private final Class<?> clazz;

		/**
		 * 方法-参数名数组映射
		 */
		private final Map<Executable, String[]> executableMap;

		/**
		 * 新建一个{@link ParameterNameDiscoveringVisitor}实例
		 *
		 * @param clazz         方法声明类
		 * @param executableMap 方法-参数名数组映射
		 */
		public ParameterNameDiscoveringVisitor(Class<?> clazz, Map<Executable, String[]> executableMap) {
			//SpringAsmInfo.ASM_VERSION:用于Spring的ASM访问者实现的ASM兼容版本：当前为
			// {@link Opcodes#ASM7}，从SpringFramework5.1开始
			super(SpringAsmInfo.ASM_VERSION);
			this.clazz = clazz;
			this.executableMap = executableMap;
		}

		/**
		 * 根据给定方法的访问标记确定是否是合成或者是桥接方法
		 *
		 * @param access 方法的访问标记
		 * @return 如果是合成或者是桥接方法，返回true；否则返回false
		 */
		private static boolean isSyntheticOrBridged(int access) {
			return (((access & Opcodes.ACC_SYNTHETIC) | (access & Opcodes.ACC_BRIDGE)) > 0);
		}

		/**
		 * 根据给定方法的访问标记确定是否是静态方法
		 *
		 * @param access 方法的访问标记
		 * @return 如果是静态方法，返回true；否则返回false
		 */
		private static boolean isStatic(int access) {
			return ((access & Opcodes.ACC_STATIC) > 0);
		}

		@Override
		@Nullable
		public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
			// exclude synthetic + bridged && static class initialization
			// 排除合成+桥接 && 静态类初始化
			if (!isSyntheticOrBridged(access) && !STATIC_CLASS_INIT.equals(name)) {
				return new LocalVariableTableVisitor(this.clazz, this.executableMap, name, desc, isStatic(access));
			}
			return null;
		}
	}


	/**
	 * 本地变量表访问器
	 */
	private static class LocalVariableTableVisitor extends MethodVisitor {

		/**
		 * 构造函数名
		 */
		private static final String CONSTRUCTOR = "<init>";

		/**
		 * 方法声明类
		 */
		private final Class<?> clazz;

		/**
		 * 方法-方法参数名数组
		 */
		private final Map<Executable, String[]> executableMap;

		/**
		 * 方法名
		 */
		private final String name;

		/**
		 * 方法参数对应的{@link org.springframework.asm.Type}
		 */
		private final Type[] args;

		/**
		 * 方法参数名
		 */
		private final String[] parameterNames;

		/**
		 * 是否是静态方法的标记
		 */
		private final boolean isStatic;
		/**
		 * The nth entry contains the slot index of the LVT table entry holding the
		 * argument name for the nth parameter.
		 * <p>第n个条目包含LVT表条目的插槽索引，该条目保留第n个参数的参数名称。</p>
		 * <p>lVT 就是 LocalVariableTable 缩写，相关文章：https://www.jianshu.com/p/876eaa14a0a9</p>
		 */
		private final int[] lvtSlotIndex;
		/**
		 * 有lvt信息标记
		 * <p>lVT 就是 LocalVariableTable 缩写，相关文章：https://www.jianshu.com/p/876eaa14a0a9</p>
		 */
		private boolean hasLvtInfo = false;

		/**
		 * @param clazz    方法声明类
		 * @param map      方法-方法参数名数组
		 * @param name     方法名
		 * @param desc     方法的描述符（参见{@link Type}）
		 * @param isStatic 是否是静态方法
		 */
		public LocalVariableTableVisitor(Class<?> clazz, Map<Executable, String[]> map, String name, String desc, boolean isStatic) {
			//用于Spring的ASM访问者实现的ASM兼容版本：当前为Opcodes.ASM7，从 SpringFramework5.1开始
			super(SpringAsmInfo.ASM_VERSION);
			this.clazz = clazz;
			this.executableMap = map;
			this.name = name;
			//获取desc的参数类型相对应的Type值
			this.args = Type.getArgumentTypes(desc);
			this.parameterNames = new String[this.args.length];
			this.isStatic = isStatic;
			//计算出lvt插槽指数数组的每个值
			this.lvtSlotIndex = computeLvtSlotIndices(isStatic, this.args);
		}

		/**
		 * 计算lvt插槽指数
		 *
		 * @param isStatic   是否静态
		 * @param paramTypes 参数类型
		 * @return
		 */
		private static int[] computeLvtSlotIndices(boolean isStatic, Type[] paramTypes) {
			//lvt插槽指数索引数组
			int[] lvtIndex = new int[paramTypes.length];
			//下一个索引位置，如果是静态就为0，否则为1，因为实例方法，前面第0个位置还有个this
			int nextIndex = (isStatic ? 0 : 1);
			//遍历参数类型数组
			for (int i = 0; i < paramTypes.length; i++) {
				//设置第i个lvt索引为nextIndex
				lvtIndex[i] = nextIndex;
				//如果参数类型为LONG类型或者是DOUBLE类型，因为如果是long和double需要
				// 2个连续的局部变量表来保存
				if (isWideType(paramTypes[i])) {
					//nextIndex+2位
					nextIndex += 2;
				} else {
					//nextIndex+1
					nextIndex++;
				}
			}
			//lvt插槽指数索引数组
			return lvtIndex;
		}

		/**
		 * 确定给定的{@link Type}是否为LONG类型或者是DOUBLE类型
		 *
		 * @param aType 参数类型
		 * @return 给定的{@link Type}为LONG类型或者是DOUBLE类型时返回true；否则返回false
		 */
		private static boolean isWideType(Type aType) {
			// float is not a wide type -- float不是宽类型
			return (aType == Type.LONG_TYPE || aType == Type.DOUBLE_TYPE);
		}

		@Override
		public void visitLocalVariable(String name, String description, String signature, Label start, Label end, int index) {
			//当触发该方法，就表示有LocalVariableTable信息，所以设置为true
			this.hasLvtInfo = true;
			//遍历LocalVariableTable插槽索引数组
			for (int i = 0; i < this.lvtSlotIndex.length; i++) {
				//如果第i个LocalVariableTable插槽索引等于该局部变量的索引
				if (this.lvtSlotIndex[i] == index) {
					//第i个参数名就为该局部变量名
					this.parameterNames[i] = name;
				}
			}
		}

		@Override
		public void visitEnd() {
			//如果存在LocalVariableTable的信息 或者 该方法为静态方法且该方法没有参数
			if (this.hasLvtInfo || (this.isStatic && this.parameterNames.length == 0)) {
				// visitLocalVariable will never be called for static no args methods
				// which doesn't use any local variables.
				// 不会使用任何局部变量的静态无参方法从不调用visitLocalVariable
				// This means that hasLvtInfo could be false for that kind of methods
				// even if the class has local variable info.
				// 这意味着即使类具有局部变量信息，hasLvtInfo对于这种方法也可能为false
				// 解析出Constructor/Method对象,并将其与参数名数组的关系映射添加到
				// 	方法-方法参数名数组映射中
				this.executableMap.put(resolveExecutable(), this.parameterNames);
			}
		}

		/**
		 * 解析出Constructor/Method对象
		 *
		 * @return Constructor/Method对象
		 */
		private Executable resolveExecutable() {
			//获取方法声明类的类加载器
			ClassLoader loader = this.clazz.getClassLoader();
			//初始化参数类型数组，长度为args的长度
			Class<?>[] argTypes = new Class<?>[this.args.length];
			//遍历args
			for (int i = 0; i < this.args.length; i++) {
				//将第i个arg的全类名解析成Class实例，支持原始类型(如'int')和数组类型名 (如'String[]').
				// 这实际等效于具有相投参数的forName方法，唯一的区别 是在类加载的情况下引发异常
				argTypes[i] = ClassUtils.resolveClassName(this.args[i].getClassName(), loader);
			}
			try {
				//如果方法名是构造函数名
				if (CONSTRUCTOR.equals(this.name)) {
					//获取方法声明类中的调用argTypes参数类型数组的Constructor对象并返回出去
					return this.clazz.getDeclaredConstructor(argTypes);
				}
				//到这一步表示获取的是方法，则获取在方法声明类中方法名为name，参数类型数组为
				// argTypes的Method对象并返回出去
				return this.clazz.getDeclaredMethod(this.name, argTypes);
			} catch (NoSuchMethodException ex) {
				//捕捉没有这样方法异常，抛出非法状态异常，异常信息为：方法[this.name]在.class文件中
				// 发现，但无法在class对象中解析
				throw new IllegalStateException("Method [" + this.name +
						"] was discovered in the .class file but cannot be resolved in the class object", ex);
			}
		}
	}

}
