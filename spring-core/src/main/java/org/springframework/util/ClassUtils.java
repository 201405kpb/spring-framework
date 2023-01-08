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

package org.springframework.util;

import org.springframework.lang.Nullable;

import java.io.Closeable;
import java.io.Externalizable;
import java.io.Serializable;
import java.lang.reflect.*;
import java.util.*;

/**
 * Miscellaneous {@code java.lang.Class} utility methods.
 * Mainly for internal use within the framework.
 * 其他的{@code java.lang.Class}使用方法。主要供框架内部使用
 *
 * @author Juergen Hoeller
 * @author Keith Donald
 * @author Rob Harrop
 * @author Sam Brannen
 * @since 1.1
 * @see TypeUtils
 * @see ReflectionUtils
 */
public abstract class ClassUtils {

	/** Suffix for array class names: {@code "[]"}.
	 * 数组类名的后缀：'[]' */
	public static final String ARRAY_SUFFIX = "[]";
	/** The CGLIB class separator: {@code "$$"}.
	 * CGLIB类分割符：'$$'*/
	public static final String CGLIB_CLASS_SEPARATOR = "$$";
	/** The ".class" file suffix.
	 * class文件后缀：'.class' */
	public static final String CLASS_FILE_SUFFIX = ".class";
	/** Prefix for internal array class names: {@code "["}.
	 * 内部数组类名前缀：'[' */
	private static final String INTERNAL_ARRAY_PREFIX = "[";
	/** Prefix for internal non-primitive array class names: {@code "[L"}.
	 * 内部非原始数组类名前缀：'[L */
	private static final String NON_PRIMITIVE_ARRAY_PREFIX = "[L";
	/** A reusable empty class array constant.
	 * */
	private static final Class<?>[] EMPTY_CLASS_ARRAY = {};
	/** The package separator character: {@code '.'}.
	 * 包名分割符：'.' */
	private static final char PACKAGE_SEPARATOR = '.';
	/** The path separator character: {@code '/'}.
	 * 路径分割符:'/' */
	private static final char PATH_SEPARATOR = '/';
	/** The nested class separator character: {@code '$'}.
	 * 内部类分割符:'$' */
	private static final char NESTED_CLASS_SEPARATOR = '$';
	/**
	 * Map with primitive wrapper type as key and corresponding primitive
	 * type as value, for example: Integer.class -> int.class.
	 * 使用原始包装类作为key和相应原始类作为value的映射，如：Integer.class -> int.class
	 */
	private static final Map<Class<?>, Class<?>> primitiveWrapperTypeMap = new IdentityHashMap<>(9);

	/**
	 * Map with primitive type as key and corresponding wrapper
	 * type as value, for example: int.class -> Integer.class.
	 * 使用原始类作为key和相应的原始包装类作为value的映射，如：int.class -> Integer.class
	 */
	private static final Map<Class<?>, Class<?>> primitiveTypeToWrapperMap = new IdentityHashMap<>(9);

	/**
	 * Map with primitive type name as key and corresponding primitive
	 * type as value, for example: "int" -> "int.class".
	 * 使用原始类名作为key和相应的原始类作为value的映射，如：'int' -> int.class
	 */
	private static final Map<String, Class<?>> primitiveTypeNameMap = new HashMap<>(32);

	/**
	 * Map with common Java language class name as key and corresponding Class as value.
	 * Primarily for efficient deserialization of remote invocations.
	 * 用通用的Java语言类名作为key和对应的类作为vale的映射。主要用于远程调用的有效反序列化
	 */
	private static final Map<String, Class<?>> commonClassCache = new HashMap<>(64);

	/**
	 * Common Java language interfaces which are supposed to be ignored
	 * when searching for 'primary' user-level interfaces.
	 *  搜索'primary'用户级接口时应该忽略的通用Java语言接口
	 */
	private static final Set<Class<?>> javaLanguageInterfaces;

	/**
	 * Cache for equivalent methods on an interface implemented by the declaring class.
	 */
	private static final Map<Method, Method> interfaceMethodCache = new ConcurrentReferenceHashMap<>(256);


	static {
		//将所有原始包装类和原始类添加到primitiveWrapperTypeMap
		primitiveWrapperTypeMap.put(Boolean.class, boolean.class);
		primitiveWrapperTypeMap.put(Byte.class, byte.class);
		primitiveWrapperTypeMap.put(Character.class, char.class);
		primitiveWrapperTypeMap.put(Double.class, double.class);
		primitiveWrapperTypeMap.put(Float.class, float.class);
		primitiveWrapperTypeMap.put(Integer.class, int.class);
		primitiveWrapperTypeMap.put(Long.class, long.class);
		primitiveWrapperTypeMap.put(Short.class, short.class);
		primitiveWrapperTypeMap.put(Void.class, void.class);

		// Map entry iteration is less expensive to initialize than forEach with lambdas
		// 与使用lambda的forEach相比,映射条目迭代的初始化成本更低
		//遍历primitiveWrapperTypeMap
		for (Map.Entry<Class<?>, Class<?>> entry : primitiveWrapperTypeMap.entrySet()) {
			//将primitiveWrapperTypeMap的每个条目存储的原始类和原始包装类添加到primitiveTypeToWrapperMap中
			primitiveTypeToWrapperMap.put(entry.getValue(), entry.getKey());
			//注册entry的原始包装类到ClassUtils的缓存中
			registerCommonClasses(entry.getKey());
		}

		//定义一个初始容量为32的HashSet，用于存储原始类型
		Set<Class<?>> primitiveTypes = new HashSet<>(32);
		//将primitiveWrapperTypeMap存储的原始类型添加到primitiveTypes中
		primitiveTypes.addAll(primitiveWrapperTypeMap.values());
		//将所有原始类型数组添加到primitiveTypes中
		Collections.addAll(primitiveTypes, boolean[].class, byte[].class, char[].class,
				double[].class, float[].class, int[].class, long[].class, short[].class);
		//将void类添加到primitiveTypes中
		for (Class<?> primitiveType : primitiveTypes) {
			//取出primitiveType的类名作为key,primitiveType本身作为value添加到primitiveTypeNameMap中
			primitiveTypeNameMap.put(primitiveType.getName(), primitiveType);
		}
		//注册原始包装类型数组到ClassUtils的缓存中
		registerCommonClasses(Boolean[].class, Byte[].class, Character[].class, Double[].class,
				Float[].class, Integer[].class, Long[].class, Short[].class);
		//注册Number,String,Class,Object类以及数组类型到ClassUtils的缓存中
		registerCommonClasses(Number.class, Number[].class, String.class, String[].class,
				Class.class, Class[].class, Object.class, Object[].class);
		//注册常用异常类到ClassUtils的缓存中
		registerCommonClasses(Throwable.class, Exception.class, RuntimeException.class,
				Error.class, StackTraceElement.class, StackTraceElement[].class);
		//注册枚举类，常用集合类，迭代器类到ClassUtils缓存中
		registerCommonClasses(Enum.class, Iterable.class, Iterator.class, Enumeration.class,
				Collection.class, List.class, Set.class, Map.class, Map.Entry.class, Optional.class);
		//定义常用java语言接口数组，存放着序列化，反序列化，可关闭，自动关闭，克隆，比较接口
		Class<?>[] javaLanguageInterfaceArray = {Serializable.class, Externalizable.class,
				Closeable.class, AutoCloseable.class, Cloneable.class, Comparable.class};
		//注册序列化，反序列化，可关闭，自动关闭，克隆，比较接口到ClassUtils缓存中
		registerCommonClasses(javaLanguageInterfaceArray);
		//将序列化，反序列化，可关闭，自动关闭，克隆，比较接口添加到javaLanguageInterfaces中
		javaLanguageInterfaces = Set.of(javaLanguageInterfaceArray);
	}


	/**
	 * Register the given common classes with the ClassUtils cache.
	 * 注册给定的通用类到ClassUtils的缓存中
	 */
	private static void registerCommonClasses(Class<?>... commonClasses) {
		//遍历commonClasses
		for (Class<?> clazz : commonClasses) {
			//将clazz的类名作为key,clazz作为value添加到commonClassCache中
			commonClassCache.put(clazz.getName(), clazz);
		}
	}

	/**
	 * 	<p>
	 * 	    获取默认类加载器，一般返回线程上下文类加载器，没有就返回加载ClassUtils的类加载器，
	 * 	 	还是没有就返回系统类加载器，最后还是没有就返回null
	 * 	</p>
	 *
	 * Return the default ClassLoader to use: typically the thread context
	 * ClassLoader, if available; the ClassLoader that loaded the ClassUtils
	 * class will be used as fallback.
	 * <p>
	 *     返回默认的类加载器来使用：通常是线程上下文类加载器，如果有的话;加载ClassUtils类
	 * 	的ClassLoader将用作后备
	 * </p>
	 * <p>Call this method if you intend to use the thread context ClassLoader
	 * in a scenario where you clearly prefer a non-null ClassLoader reference:
	 * for example, for class path resource loading (but not necessarily for
	 * {@code Class.forName}, which accepts a {@code null} ClassLoader
	 * reference as well).
	 * <p>
	 *     调用这个方法如果你打算使用线程上下文类加载器，在你显然更喜欢非null
	 *     ClassLoad引用的情况下：
	 *     例如，用于类路径资源加载（但不一定适用于{@code Class.forName}),它也接受
	 *     一个{@code null}ClassLoader引用
	 * </p>
	 * @return the default ClassLoader (only {@code null} if even the system
	 * ClassLoader isn't accessible) 默认的类加载器（如果系统类加载器都无法访问只能返回null)
	 * @see Thread#getContextClassLoader()
	 * @see ClassLoader#getSystemClassLoader()
	 */
	@Nullable
	public static ClassLoader getDefaultClassLoader() {
		//定义类加载器变量c1
		ClassLoader cl = null;
		try {
			//获取当前线程的上下文类加载器
			cl = Thread.currentThread().getContextClassLoader();
		}
		catch (Throwable ex) {
			// Cannot access thread context ClassLoader - falling back...
			//不能访问线程上下文类加载器-后退
		}
		//如果cl为null
		if (cl == null) {
			// No thread context class loader -> use class loader of this class.
			// 没有线程上下文类加载器 -> 使用这个类的类加载器
			//获取ClassUtils的类加载器
			cl = ClassUtils.class.getClassLoader();
			//如果c1还为null
			if (cl == null) {
				// getClassLoader() returning null indicates the bootstrap ClassLoader
				// getClassLoader() 返回null表示boostrap ClassLoader
				try {
					//获取系统的类加载器
					cl = ClassLoader.getSystemClassLoader();
				}
				catch (Throwable ex) {
					// Cannot access system ClassLoader - oh well, maybe the caller can live with null...
					// 不能访问系统类加载器 - 哦，好吧，可能调用者可以忍受null
				}
			}
		}
		return cl;
	}

	/**
	 * Override the thread context ClassLoader with the environment's bean ClassLoader
	 * if necessary, i.e. if the bean ClassLoader is not equivalent to the thread
	 * context ClassLoader already.
	 * <p>
	 *     如果需要,用环境的Bean ClassLoader覆盖线程上下文ClassLoader.即如果Bean ClassLoader
	 *     已经不等于线程上下文ClassLoader
	 * </p>
	 * @param classLoaderToUse the actual ClassLoader to use for the thread context 用于线程上下文的实际ClassLoader
	 * @return the original thread context ClassLoader, or {@code null} if not overridden
	 * 			原来的线程上下文ClassLoader,或者如果未重写就返回null
	 */
	@Nullable
	public static ClassLoader overrideThreadContextClassLoader(@Nullable ClassLoader classLoaderToUse) {
		//获取当前线程
		Thread currentThread = Thread.currentThread();
		//获取当前线程的ClassLoader
		ClassLoader threadContextClassLoader = currentThread.getContextClassLoader();
		//如果classLoaderToUse不为null 且 classLoaderToUser不等于当前线程的ClassLoader
		if (classLoaderToUse != null && !classLoaderToUse.equals(threadContextClassLoader)) {
			//设置当前线程的ClassLoader为classLoaderToUser
			currentThread.setContextClassLoader(classLoaderToUse);
			//返回原来的线程上下文ClassLoader
			return threadContextClassLoader;
		}
		//如果classLoaderToUser为null 或者 classLoaderToUser等于当前线程的ClassLoader
		else {
			//返回null
			return null;
		}
	}

	/**
	 * <p>使用classLoader加载name对应的Class对象。该方式是Spring用于代替Class.forName()的方法，支持返回原始的类实例(如'int')
	 * 和数组类名 (如'String[]')。此外，它还能够以Java source样式解析内部类名(如:'java.lang.Thread.State'
	 * 而不是'java.lang.Thread$State')：
	 * 	<ol>
	 * 	  <li>如果name为null，抛出异常</li>
	 * 	  <li>如果name是个原始类型名【int,long,double...】，就获取其对应的Class对象,并赋值给【变量clazz】</li>
	 * 	  <li>如果clazz为null,从缓存Map【commonClassCache】中获取name对应的Class</li>
	 * 	  <li>如果clazz不为null,直接返回clazz</li>
	 * 	  <li>如果name是以'[]'结尾的，意味着name是个原始数组类名：
	 * 	  	<ol>
	 * 	  	   <li>截取出name '[]'前面的字符串赋值给【变量elementClassName】</li>
	 * 	  	   <li>传入elementClassName递归本方法获取其Class</li>
	 * 	  	   <li>新建一个elementClass类型长度为0的数组，然后获取其类型返回出去</li>
	 * 	  	</ol>
	 * 	  </li>
	 * 	  <li>如果name是以'[L'开头 且 以';'结尾,意味着name是个非原始数组类名：
	 * 	   <ol>
	 * 	     <li>截取出name '[L'到';'之间的字符串赋值给【变量elementName】</li>
	 * 	     <li>传入elementName递归本方法获取其Class</li>
	 * 	     <li>新建一个elementClass类型长度为0的数组，然后获取其类型返回出去</li>
	 * 	   </ol>
	 * 	  </li>
	 * 	  <li>如name是 '[[I'开头，或者类似于'[[Ljava.lang.String;'样式字符串，意味着name是内部数组类名：
	 * 	   <ol>
	 * 	     <li>截取出name '['后面的字符串赋值给【变量elementName】</li>
	 * 	     <li>传入elementName递归本方法获取其Class</li>
	 * 	     <li>新建一个elementClass类型长度为0的数组，然后获取其类型返回出去</li>
	 * 	   </ol>
	 * 	  </li>
	 * 	  <li>如果没有传入classloader,会获取默认类加载器，一般得到的是线程上下文类加载器,赋值给【变量clToUse】</li>
	 * 	  <li>通过clsToUse获取name对应的Class对象，但不会对class对象进行初始化操作,同时捕捉ClassNotFoundException【变量ex】。
	 * 	  成功获取class对象时会返回该class对象出去</li>
	 * 	  <li>如果抛出了ex：
	 * 	  	<ol>
	 * 	  	   <li>获取name的最后一个包名分割符'.'的索引位置</li>
	 * 	  	   <li>如果找到索引位置：
	 * 	  	   	<ol>
	 * 	  	   	  <li>尝试将name转换成内部类名,innerClassName=name的包名+'$'+name的类名,【变量innerClassName】</li>
	 * 	  	   	  <li>通过clToUse获取innerClassName对应的Class对象，并将该class对象返回出去，捕捉其加载过程中
	 * 	  	   	  的ClassNotFoundException【变量ex2】/li>
	 * 	  	   	  <li>如果ex2，就抛出ex</li>
	 * 	  	   	</ol>
	 * 	  	   </li>
	 * 	  	   <li>否则，抛出ex</li>
	 * 	  	</ol>
	 * 	  </li>
	 * 	</ol>
	 * </p>
	 * Replacement for {@code Class.forName()} that also returns Class instances
	 * for primitives (e.g. "int") and array class names (e.g. "String[]").
	 * Furthermore, it is also capable of resolving inner class names in Java source
	 * style (e.g. "java.lang.Thread.State" instead of "java.lang.Thread$State").
	 * <p>
	 *     代替{@code Class.forName()}方法，同时还返回原始的类实例(如'int')和数组类名
	 *     (如'String[]')。此外，它还能够以Java source样式解析内部类名(如:'java.lang.Thread.State'
	 *     而不是'java.lang.Thread$State')
	 * </p>
	 * @param name the name of the Class 类名
	 * @param classLoader the class loader to use
	 * (may be {@code null}, which indicates the default class loader)
	 *                    使用的类加载器(可以为null，表示为默认的类加载器)
	 * @return a class instance for the supplied name {@code name}的类实例
	 * @throws ClassNotFoundException if the class was not found 如果没有找到{@code name)对应的类
	 * @throws LinkageError if the class file could not be loaded 如果无法加载类文件
	 * @see Class#forName(String, boolean, ClassLoader)
	 */
	public static Class<?> forName(String name, @Nullable ClassLoader classLoader)
			throws ClassNotFoundException, LinkageError {
		//如果name为null，抛出异常
		Assert.notNull(name, "Name must not be null");
		//如果name是个原始类型名，就获取其对应的Class
		Class<?> clazz = resolvePrimitiveClassName(name);
		//如果clazz为null
		if (clazz == null) {
			//从缓存Map中获取name对应的Class
			clazz = commonClassCache.get(name);
		}
		//如果clazz不为null
		if (clazz != null) {
			//直接返回clazz
			return clazz;
		}

		// "java.lang.String[]" style arrays 'java.lang.String[]'样式数组，表示原始数组类名
		//如果name是以'[]'结尾的
		if (name.endsWith(ARRAY_SUFFIX)) {
			//截取出name '[]'前面的字符串赋值给elementClassName
			String elementClassName = name.substring(0, name.length() - ARRAY_SUFFIX.length());
			//传入elementClassName递归本方法获取其Class
			Class<?> elementClass = forName(elementClassName, classLoader);
			//新建一个elementClass类型长度为0的数组，然后获取其类型返回出去
			return Array.newInstance(elementClass, 0).getClass();
		}

		// "[Ljava.lang.String;" style arrays '[Ljava.lang.Stirng'样式数组，表示非原始数组类名
		//如果names是以'[L'开头 且 以';'结尾
		if (name.startsWith(NON_PRIMITIVE_ARRAY_PREFIX) && name.endsWith(";")) {
			//截取出name '[L'到';'之间的字符串赋值给elementName
			String elementName = name.substring(NON_PRIMITIVE_ARRAY_PREFIX.length(), name.length() - 1);
			//传入elementClassName递归本方法获取其Class
			Class<?> elementClass = forName(elementName, classLoader);
			//新建一个elementClass类型长度为0的数组，然后获取其类型返回出去
			return Array.newInstance(elementClass, 0).getClass();
		}

		// "[[I" or "[[Ljava.lang.String;" style arrays '[[I' 或者 '[[Ljava.lang.String;'样式数组，表示内部数组类名
		if (name.startsWith(INTERNAL_ARRAY_PREFIX)) {
			//截取出name '['后面的字符串赋值给elementName
			String elementName = name.substring(INTERNAL_ARRAY_PREFIX.length());
			//传入elementName递归本方法获取其Class
			Class<?> elementClass = forName(elementName, classLoader);
			//新建一个elementClass类型长度为0的数组，然后获取其类型返回出去
			return Array.newInstance(elementClass, 0).getClass();
		}

		//将classLoader赋值给clToUse变量
		ClassLoader clToUse = classLoader;
		//如果clToUse为null
		if (clToUse == null) {
			//获取默认类加载器，一般返回线程上下文类加载器，没有就返回加载ClassUtils的类加载器，
			//还是没有就返回系统类加载器，最后还是没有就返回null
			clToUse = getDefaultClassLoader();
		}
		try {
			//Class.forName(String,boolean,ClassLoader):返回与给定的字符串名称相关联类或接口的Class对象。
			//第一个参数:类的全名
			//第二参数:是否进行初始化操作，如果指定参数initialize为false时，也不会触发类初始化，其实这个参数是告诉虚拟机，是否要对类进行初始化。
			//第三参数:加载时使用的类加载器。
			//通过clsToUse获取name对应的Class对象
			return Class.forName(name, false, clToUse);
		}
		catch (ClassNotFoundException ex) {
			//如果找到不到类时
			//获取最后一个包名分割符'.'的索引位置
			int lastDotIndex = name.lastIndexOf(PACKAGE_SEPARATOR);
			if (lastDotIndex != -1) {
				//name.substring(0, lastDotIndex):截取出name的包名
				//name.substring(lastDotIndex + 1):截取出name的类名
				//尝试将name转换成内部类名,innerClassName=name的包名+'$'+name的类名
				String nestedClassName =
						name.substring(0, lastDotIndex) + NESTED_CLASS_SEPARATOR + name.substring(lastDotIndex + 1);
				try {
					//通过clToUse获取innerClassName对应的Class对象
					return Class.forName(nestedClassName, false, clToUse);
				}
				catch (ClassNotFoundException ex2) {
					// Swallow - let original exception get through 吞噬 - 让原异常通过
				}
			}
			//当将name转换成内部类名仍然获取不到Class对象时,抛出异常
			throw ex;
		}
	}

	/**
	 * Resolve the given class name into a Class instance. Supports
	 * primitives (like "int") and array class names (like "String[]").
	 * <p>
	 *     将给定的类名解析成Class实例，支持原始类型(如'int')和数组类型名
	 *     (如'String[]').
	 * </p>
	 * <p>This is effectively equivalent to the {@code forName}
	 * method with the same arguments, with the only difference being
	 * the exceptions thrown in case of class loading failure.
	 * <p>
	 *		这实际等效于具有相投参数的{@code forName}方法，唯一的区别
	 *		是在类加载的情况下引发异常
	 * </p>
	 * @param className the name of the Class 类名
	 * @param classLoader the class loader to use
	 * (may be {@code null}, which indicates the default class loader)
	 *                    使用的类加载器(如果为null，表示是默认的类加载器)
	 * @return a class instance for the supplied name 对应{@code className}的类实例
	 * @throws IllegalArgumentException if the class name was not resolvable
	 * (that is, the class could not be found or the class file could not be loaded)
	 * 		如果类名不可解析(即找不到类名或者无法加载类文件)
	 * @throws IllegalStateException if the corresponding class is resolvable but
	 * there was a readability mismatch in the inheritance hierarchy of the class
	 * (typically a missing dependency declaration in a Jigsaw module definition
	 * for a superclass or interface implemented by the class to be loaded here)
	 * 		如果对应的类是可解析的，但是他在类的继承层次中存在可读性不匹配的情况
	 * 		(在一个jigsaw模块通过此处加载类定义一个超类或者接口实现，通常会缺少依赖声明)
	 * @see #forName(String, ClassLoader)
	 */
	public static Class<?> resolveClassName(String className, @Nullable ClassLoader classLoader)
			throws IllegalArgumentException {

		try {
			//使用classLoader加载className对应的Class对象
			return forName(className, classLoader);
		}
		catch (IllegalAccessError err) {
			//类的继承层次结构中的可读性不匹配时，抛出异常
			throw new IllegalStateException("Readability mismatch in inheritance hierarchy of class [" +
					className + "]: " + err.getMessage(), err);
		}
		catch (LinkageError err) {
			//无法解析类定义时，抛出异常
			throw new IllegalArgumentException("Unresolvable class definition for class [" + className + "]", err);
		}
		catch (ClassNotFoundException ex) {
			//没有找到对应类时，抛出异常
			throw new IllegalArgumentException("Could not find class [" + className + "]", ex);
		}
	}

	/**
	 * Determine whether the {@link Class} identified by the supplied name is present
	 * and can be loaded. Will return {@code false} if either the class or
	 * one of its dependencies is not present or cannot be loaded.
	 * <p>
	 *     确定是否存在{@code className}对应的 {@link Class}并且可以加载。
	 *     如果该类或者该类的依赖项不存在或者无法加载时，返回false
	 * </p>
	 * @param className the name of the class to check 要检查类名
	 * @param classLoader the class loader to use 使用的类加载器(如果为null，表示使用默认的类加载器)
	 * (may be {@code null} which indicates the default class loader)
	 * @return whether the specified class is present (including all of its
	 * superclasses and interfaces) 指定的类是否存在(包括该类的所有父类和接口)
	 * @throws IllegalStateException if the corresponding class is resolvable but
	 * there was a readability mismatch in the inheritance hierarchy of the class
	 * (typically a missing dependency declaration in a Jigsaw module definition
	 * for a superclass or interface implemented by the class to be checked here)
	 * 		如果对应的类是可解析的，但是他在类的继承层次中存在可读性不匹配的情况
	 * 		(在一个jigsaw模块通过此处加载类定义一个超类或者接口实现，通常会缺少依赖声明)
	 */
	public static boolean isPresent(String className, @Nullable ClassLoader classLoader) {
		try {
			//使用classLoader加载className对应的Class对象
			forName(className, classLoader);
			//没有抛出异常的情况下，表示找到对应的Class对象,并且该Class对象可用。所以直接返回true
			return true;
		}
		catch (IllegalAccessError err) {
			//类的继承层次结构中的可读性不匹配时，抛出异常
			throw new IllegalStateException("Readability mismatch in inheritance hierarchy of class [" +
					className + "]: " + err.getMessage(), err);
		}
		catch (Throwable ex) {
			// Typically ClassNotFoundException or NoClassDefFoundError...
			// 通常是没有找到类异常或者没有找到类定义异常时才会进入该方法
			//这里捕捉这些异常，然后返回false，表示使用classLoader加载className对应的Class并不存在
			return false;
		}
	}

	/**
	 * Check whether the given class is visible in the given ClassLoader.
	 * 检查给定的类对象在给定的类加载器中是否可见
	 * @param clazz the class to check (typically an interface) 检查的类（通常是一个接口）
	 * @param classLoader the ClassLoader to check against
	 * (may be {@code null} in which case this method will always return {@code true})
	 *                    要检查的类加载器（如果为null,在这种情况下该方法将始终返回true)
	 */
	public static boolean isVisible(Class<?> clazz, @Nullable ClassLoader classLoader) {
		//如果classLoader为null
		if (classLoader == null) {
			//直接返回true
			return true;
		}
		try {
			//获取clazz的ClassLoader对象，如果与classLoader的同一个
			if (clazz.getClassLoader() == classLoader) {
				//直接返回true
				return true;
			}
		}
		catch (SecurityException ex) {
			// fall through to loadable check below 进入下面的可加载检查
		}

		// Visible if same Class can be loaded from given ClassLoader
		// 如果可以从给定的ClassLoader中加载相同的Class对象,就表示可见
		return isLoadable(clazz, classLoader);
	}

	/**
	 * Check whether the given class is cache-safe in the given context,
	 * i.e. whether it is loaded by the given ClassLoader or a parent of it.
	 * <p>
	 *     检查给定的类对象在给定的上下文中是否缓存安全，即判断是否由
	 *     给定的类加载器或者给定的类加载的父级类加载器加载过
	 * </p>
	 * @param clazz the class to analyze 要分析的类对象
	 * @param classLoader the ClassLoader to potentially cache metadata in
	 * (may be {@code null} which indicates the system class loader)
	 *                       可能会缓存元数据的类夹杂器(如果为null,表示使用系统类加载器)
	 */
	public static boolean isCacheSafe(Class<?> clazz, @Nullable ClassLoader classLoader) {
		//如果clazz为null，抛出异常
		Assert.notNull(clazz, "Class must not be null");
		try {
			//获取clazz的类加载器
			ClassLoader target = clazz.getClassLoader();
			// Common cases 常见情况
			//如果target等于给定的类加载器或者target为null
			if (target == classLoader || target == null) {
				//直接返回true
				return true;
			}
			//如果给定的类加载器为null
			if (classLoader == null) {
				//直接返回false
				return false;
			}
			// Check for match in ancestors -> positive 检查是否匹配祖先 -> 肯定
			//将给定的类加载器赋值给current
			ClassLoader current = classLoader;
			//遍历,只要current不为null，
			while (current != null) {
				//获取当前类加载器的父级类加载器并赋值给current
				current = current.getParent();
				//如果当前类加载器是clazz的类加载器
				if (current == target) {
					//直接返回true
					return true;
				}
			}
			// Check for match in children -> negative 检查是否匹配子级 -》否定
			//遍历，只要target不为null
			while (target != null) {
				//获取target的父级类加载器并赋值给target
				target = target.getParent();
				//如果target是给定的类加载器
				if (target == classLoader) {
					//直接返回false
					return false;
				}
			}
		}
		catch (SecurityException ex) {
			// Fall through to loadable check below 进入下面的可加载检查
		}

		// Fallback for ClassLoaders without parent/child relationship:
		// safe if same Class can be loaded from given ClassLoader
		//没有父/子关系的类加载器后备
		//如果可以从给定的类加载器中加载同一个Class对象，则表示安全。
		//如果classLoader不为null 且 如果clazz可在classloader中加载，则返回true；否则返回false
		return (classLoader != null && isLoadable(clazz, classLoader));
	}

	/**
	 * Check whether the given class is loadable in the given ClassLoader.
	 * <p>
	 *     检查给定的class对象是否可在给定的类加载器中加载
	 * </p>
	 * @param clazz the class to check (typically an interface) 检查的类(通常是个接口)
	 * @param classLoader the ClassLoader to check against 检查类加载器
	 * @since 5.0.6
	 */
	private static boolean isLoadable(Class<?> clazz, ClassLoader classLoader) {
		try {
			//获取clazz的全类名让classLoader加载对应的Class对象，如果该class对象等于clazz，返回true;否则返回false
			return (clazz == classLoader.loadClass(clazz.getName()));
			// Else: different class with same name found 其他：同一个全类名找到不同的类对象
		}
		catch (ClassNotFoundException ex) {
			// No corresponding class found at all 完全找不到对应的类对象,直接返回false
			return false;
		}
	}

	/**
	 * Resolve the given class name as primitive class, if appropriate,
	 * according to the JVM's naming rules for primitive classes.
	 * <p>
	 *     将给定的类名解析为原始类，如果合适，根据JVM对原始类的命名规则。
	 * </p>
	 * <p>Also supports the JVM's internal class names for primitive arrays.
	 * Does <i>not</i> support the "[]" suffix notation for primitive arrays;
	 * this is only supported by {@link #forName(String, ClassLoader)}.
	 * <p>
	 *     同时支持原始数组的JVM内部类名。但是不支持以'[]'作为后缀符合的原始数组；
	 *     这仅有{@link #forName(String, ClassLoader)} 支持
	 * </p>
	 * @param name the name of the potentially primitive class 潜在原始类名
	 * @return the primitive class, or {@code null} if the name does not denote
	 * a primitive class or primitive array class
	 * 		原始类，或者如果name没有表示一个原始类或者原始数组类返回null
	 */
	@Nullable
	public static Class<?> resolvePrimitiveClassName(@Nullable String name) {
		Class<?> result = null;
		// Most class names will be quite long, considering that they
		// SHOULD sit in a package, so a length check is worthwhile.
		//考虑到应该将它们放在包装中，大多数类名都将很长，因此进行长度检查是值得的
		//如果name不为null 且 name的长度小于等于8
		if (name != null && name.length() <= 8) {
			// Could be a primitive - likely. 可能是原始类型 - 可能的
			//从primitiveTypeNameMap中获取name对应的Class
			result = primitiveTypeNameMap.get(name);
		}
		return result;
	}

	/**
	 * Check if the given class represents a primitive wrapper,
	 * i.e. Boolean, Byte, Character, Short, Integer, Long, Float, Double, or
	 * Void.
	 * <p>
	 *     检查给定的类对象是否表示原始包装类，即Boolean, Byte, Character, Short,
	 *     Integer, Long, Float, Double, or Void.
	 * </p>
	 * @param clazz the class to check 检查类对象
	 * @return whether the given class is a primitive wrapper class 给定的类对象是否表示原始包装类
	 */
	public static boolean isPrimitiveWrapper(Class<?> clazz) {
		//如果clazz为null抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//判断clazz在primitiveWrapperTypeMap中是否存在
		return primitiveWrapperTypeMap.containsKey(clazz);
	}

	/**
	 * Check if the given class represents a primitive (i.e. boolean, byte,
	 * char, short, int, long, float, or double), {@code void}, or a wrapper for
	 * those types (i.e. Boolean, Byte, Character, Short, Integer, Long, Float,
	 * Double, or Void).
	 * <p>
	 *     检查给定的类对象是否是原始类型(即boolean, byte,
	 * 	   char, short, int, long, float, or double), {@code void}）,或者是原始包装
	 * 	   类(即Boolean, Byte, Character, Short, Integer, Long, Float,
	 * 	   Double, or Void)
	 * </p>
	 * @param clazz the class to check 检查类对象
	 * @return {@code true} if the given class represents a primitive, void, or
	 * a wrapper class 如果给定的类对象是原始类型或者原始包装类，返回true。
	 */
	public static boolean isPrimitiveOrWrapper(Class<?> clazz) {
		//如果clazz不为null
		Assert.notNull(clazz, "Class must not be null");
		//如果clazz是原始类型 或者 clazz是原始包装类，返回true；否则返回false
		return (clazz.isPrimitive() || isPrimitiveWrapper(clazz));
	}

	/**
	 * Check if the given class represents an array of primitives,
	 * i.e. boolean, byte, char, short, int, long, float, or double.
	 * <p>
	 *     检查给定的类对象是否是原始类型数组(即boolean, byte, char, short,
	 *     int, long, float, or double.)
	 * </p>
	 * @param clazz the class to check 检查类对象
	 * @return whether the given class is a primitive array class 给定的类对象是否是原始类型数组
	 */
	public static boolean isPrimitiveArray(Class<?> clazz) {
		//如果clazz不为null
		Assert.notNull(clazz, "Class must not be null");
		//如果clazz是数组类型 且 clazz的元素类型是原始类型
		return (clazz.isArray() && clazz.getComponentType().isPrimitive());
	}

	/**
	 * Check if the given class represents an array of primitive wrappers,
	 * i.e. Boolean, Byte, Character, Short, Integer, Long, Float, or Double.
	 * <p>
	 *     检查给定的类对象是否是原始包装类数组(即Boolean, Byte, Character, Short,
	 *     Integer, Long, Float, or Double.)
	 * </p>
	 * @param clazz the class to check 检查类对象
	 * @return whether the given class is a primitive wrapper array class 给定的类对象是否是原始包装类数组
	 */
	public static boolean isPrimitiveWrapperArray(Class<?> clazz) {
		//如果clazz为null，抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//如果clazz是数组 且 clazz的元素类型是原始包装类
		return (clazz.isArray() && isPrimitiveWrapper(clazz.getComponentType()));
	}

	/**
	 * Resolve the given class if it is a primitive class,
	 * returning the corresponding primitive wrapper type instead.
	 * <p>
	 *     如果给定的类是原始类型，则对其进行解析，
	 *     返回对应原始类型的原始包装类
	 * </p>
	 * @param clazz the class to check 检查的类
	 * @return the original class, or a primitive wrapper for the original primitive type
	 * 		原来的类对象，或者原始类型的原始包装类
	 */
	public static Class<?> resolvePrimitiveIfNecessary(Class<?> clazz) {
		//如果clazz为null，抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//如果clazz是原始类型且clazz不是void,返回在pritiveTypeToWrapperMap对应clazz的原始包装类，否则直接返回clazz
		return (clazz.isPrimitive() && clazz != void.class ? primitiveTypeToWrapperMap.get(clazz) : clazz);
	}

	/**
	 * Check if the right-hand side type may be assigned to the left-hand side
	 * type, assuming setting by reflection. Considers primitive wrapper
	 * classes as assignable to the corresponding primitive types.
	 * <p>
	 *     检查是否可以将右侧类型分配给左侧类型，假设可以通过反射进行设置。
	 *     将原始包装类视为可分配给相应的原始类型。
	 * </p>
	 * @param lhsType the target type 目标类型
	 * @param rhsType the value type that should be assigned to the target type 应该分配给目标类型的类型值
	 * @return if the target type is assignable from the value type 如果可以从类型值中分配目标类型
	 */
	public static boolean isAssignable(Class<?> lhsType, Class<?> rhsType) {
		//如果lhsType为null，抛出异常
		Assert.notNull(lhsType, "Left-hand side type must not be null");
		//如果rhsType为null,抛出异常
		Assert.notNull(rhsType, "Right-hand side type must not be null");
		//isAssignableFrom:从类继承的角度去判断,判断是否为某个类的父类或其本身.(父类.class.isAssignableFrom(子类.class))
		//如果lhsType是否rhsType的父类或其本身
		if (lhsType.isAssignableFrom(rhsType)) {
			//直接返回true
			return true;
		}
		//如果lhsType是原始类型
		if (lhsType.isPrimitive()) {
			//获取rhsType在primitiveWrapperTypeMap对应的原始类型
			Class<?> resolvedPrimitive = primitiveWrapperTypeMap.get(rhsType);
			//如果lhsType是lhsType的原始类型
			if (lhsType == resolvedPrimitive) {
				//直接返回true
				return true;
			}
		}
		//如果lhsType不是原始类型
		else {
			//获取rhsType在primitiveWrapperTypeMap对应的原始类型
			Class<?> resolvedWrapper = primitiveTypeToWrapperMap.get(rhsType);
			//如果resolvedWrapper不为null 且 lhsType是resolvedWrpper的父类或其本身
			if (resolvedWrapper != null && lhsType.isAssignableFrom(resolvedWrapper)) {
				//直接返回true
				return true;
			}
		}
		//lhsType和rhsType都不是对应的父/子类关系，或者都不是原始类型/原始包装类型时返回false
		return false;
	}


	/**
	 * <p>确定value是否是type的实例</p>
	 * Determine if the given type is assignable from the given value,
	 * assuming setting by reflection. Considers primitive wrapper classes
	 * as assignable to the corresponding primitive types.
	 * <p>
	 *     根据给定值确定给定类是否可分配，假设通过反射进行设置。
	 *     将原始包装类视为可分配给相应的原始类型。
	 * </p>
	 * @param type the target type 目标类
	 * @param value the value that should be assigned to the type  应该分配给目标类型的值
	 * @return if the type is assignable from the value 如果给定的类是可以从值中分配的
	 */
	public static boolean isAssignableValue(Class<?> type, @Nullable Object value) {
		//如果type为null,抛出异常
		Assert.notNull(type, "Type must not be null");
		//如果value不为null,获取value的类型，根据type确定value的类型是否可分配;否则只有type不是原始类型,返回true；否则返回false
		return (value != null ? isAssignable(type, value.getClass()) : !type.isPrimitive());
	}

	/**
	 * Convert a "/"-based resource path to a "."-based fully qualified class name.
	 * 转换 '/'-基于资源路径 为 '.'-基于完全限定类名
	 * @param resourcePath the resource path pointing to a class 指向类的资源路径
	 * @return the corresponding fully qualified class name 相应的完全限定类名称
	 */
	public static String convertResourcePathToClassName(String resourcePath) {
		//如果resourcePath为null，抛出异常
		Assert.notNull(resourcePath, "Resource path must not be null");
		//将resourcePath的'/'替换成'.'并返回出去
		return resourcePath.replace(PATH_SEPARATOR, PACKAGE_SEPARATOR);
	}

	/**
	 * Convert a "."-based fully qualified class name to a "/"-based resource path.
	 * 转换 '.' - 基于完全限定类名 为 '/' -基于资源路径
	 * @param className the fully qualified class name 完全限定类名
	 * @return the corresponding resource path, pointing to the class 相应的指向类的资源路径
	 */
	public static String convertClassNameToResourcePath(String className) {
		//如果className为null，抛出异常
		Assert.notNull(className, "Class name must not be null");
		//将className的'.'替换成'/'，并返回出去
		return className.replace(PACKAGE_SEPARATOR, PATH_SEPARATOR);
	}

	/**
	 * <p>
	 *     取出{@code clazz}的包名，转换成资源路径，再加上{@code resourceName}
	 *     然后返回出去。
	 * </p>
	 * Return a path suitable for use with {@code ClassLoader.getResource}
	 * (also suitable for use with {@code Class.getResource} by prepending a
	 * slash ('/') to the return value). Built by taking the package of the specified
	 * class file, converting all dots ('.') to slashes ('/'), adding a trailing slash
	 * if necessary, and concatenating the specified resource name to this.
	 * <br/>As such, this function may be used to build a path suitable for
	 * loading a resource file that is in the same package as a class file,
	 * although {@link org.springframework.core.io.ClassPathResource} is usually
	 * even more convenient.
	 * <p>
	 *     返回适合于{@code ClassLoader.getResource}一起使用路径
	 *     (通过在返回值前加上一个斜杆('/')，也适用于{@code Class.getResource}.
	 *     通过获取指定的类文件的包来构建,将所有点'.'转换成'/',在必须时添加斜杆,
	 *     并将指定的资源名称与其关联在一起。
	 *     <br/>
	 *     比如，该方法可用于构建适合加载与类文件位于同一个包中的资源文件路径，
	 *     尽管{@link org.springframework.core.io.ClassPathResource}通常更加方便
	 * </p>
	 * @param clazz the Class whose package will be used as the base 以其包为基础的Class对象
	 * @param resourceName the resource name to append. A leading slash is optional.
	 *                     			要追加的资源名称。前斜杆是可选的
	 * @return the built-up resource path 组合资源路径
	 * @see ClassLoader#getResource
	 * @see Class#getResource
	 */
	public static String addResourcePathToPackagePath(Class<?> clazz, String resourceName) {
		//如果resourceName为null，抛出异常
		Assert.notNull(resourceName, "Resource name must not be null");
		//如果resourceName不是以'/'开头
		if (!resourceName.startsWith("/")) {
			//提取出clazz的包名,并转换成资源路径，然后加上'/',再加上resourceName，然后返回出去
			return classPackageAsResourcePath(clazz) + '/' + resourceName;
		}
		//提取出clazz的包名,并转换成资源路径，在加上resourceName，然后返回出去
		return classPackageAsResourcePath(clazz) + resourceName;
	}

	/**
	 * <p>
	 *     提取出{@code clazz}的包名,并将包名的'.'替换成'/'然后返回出去
	 * </p>
	 * Given an input class object, return a string which consists of the
	 * class's package name as a pathname, i.e., all dots ('.') are replaced by
	 * slashes ('/'). Neither a leading nor trailing slash is added. The result
	 * could be concatenated with a slash and the name of a resource and fed
	 * directly to {@code ClassLoader.getResource()}. For it to be fed to
	 * {@code Class.getResource} instead, a leading slash would also have
	 * to be prepended to the returned value.
	 * <p>
	 *     给定一个输入类对象，返回一个字符串，其中包含类的包名作为路径名，
	 *     即，所有点'.'都会被替换成'/'.不会添加前导斜杆或尾部斜杆.这结果
	 *     可以用斜杆和资源名连接起来,然后直接输入{@code ClassLoader.getResource()}.
	 *     为了将其传入给{@code Class.getResource},反斜杆还必须在返回值之前加上
	 * </p>
	 * @param clazz the input class. A {@code null} value or the default
	 * (empty) package will result in an empty string ("") being returned.
	 *              输入类.传入{@code null}值或者默认(空)包会导致返回一个空字符串('')
	 * @return a path which represents the package name 表示包名称的路径
	 * @see ClassLoader#getResource
	 * @see Class#getResource
	 */
	public static String classPackageAsResourcePath(@Nullable Class<?> clazz) {
		//如果clazz为null
		if (clazz == null) {
			//直接返回空字符串
			return "";
		}
		//获取类全名
		String className = clazz.getName();
		//获取className的最后一个'.'索引位置
		int packageEndIndex = className.lastIndexOf(PACKAGE_SEPARATOR);
		//如果没有找到索引位置
		if (packageEndIndex == -1) {
			//直接返回空字符串
			return "";
		}
		//截取出packageEndIndex之前的字符串作为包名
		String packageName = className.substring(0, packageEndIndex);
		//将packageName的'.'替换成'/'然后返回出去
		return packageName.replace(PACKAGE_SEPARATOR, PATH_SEPARATOR);
	}

	/**
	 * Build a String that consists of the names of the classes/interfaces
	 * in the given array.
	 * <p>
	 *     构建一个字符串，该字符串给定的数组中的类/接口名组成
	 * </p>
	 * <p>Basically like {@code AbstractCollection.toString()}, but stripping
	 * the "class "/"interface " prefix before every class name.
	 * <p>
	 *     基本类似于{@code AbstractCollection.toString()},但删除每个类名之前
	 *     的'class/interface'前缀
	 * </p>
	 * @param classes an array of Class objects 类对象的数组
	 * @return a String of form "[com.foo.Bar, com.foo.Baz]" 形式为'[com.foo.Bar,com.foo.Baz]'的字符串
	 * @see java.util.AbstractCollection#toString()
	 */
	public static String classNamesToString(Class<?>... classes) {
		//classes转换成List集合,调用classNamesToString(Collection)方法然后返回出去
		return classNamesToString(Arrays.asList(classes));
	}

	/**
	 * Build a String that consists of the names of the classes/interfaces
	 * in the given collection.
	 * <p>
	 *     构建一个字符串，该字符串给定的数组中的类/接口名组成
	 * </p>
	 * <p>Basically like {@code AbstractCollection.toString()}, but stripping
	 * the "class "/"interface " prefix before every class name.
	 * <p>
	 *     基本类似于{@code AbstractCollection.toString()},但删除每个类名之前
	 *     的'class/interface'前缀
	 * </p>
	 * @param classes a Collection of Class objects (may be {@code null}) 一个类对象的Conllection对象（可以为null)
	 * @return a String of form "[com.foo.Bar, com.foo.Baz]" 形式为'[com.foo.Bar,com.foo.Baz]'的字符串
	 * @see java.util.AbstractCollection#toString()
	 */
	public static String classNamesToString(@Nullable Collection<Class<?>> classes) {
		//如果clases为null或者classes是空集合
		if (CollectionUtils.isEmpty(classes)) {
			//直接返回'[]'
			return "[]";
		}
		//StringJsoner是Java8新出的一个类，用于构造由分隔符分隔的字符序列，并可选择性地从提供的前缀开始和
		// 以提供的后缀结尾。省的我们开发人员再次通过StringBuffer或者StingBuilder拼接。
		StringJoiner stringJoiner = new StringJoiner(", ", "[", "]");
		//遍历classes
		for (Class<?> clazz : classes) {
			//获取clazz的类全名，然后添加到stringJoiner中
			stringJoiner.add(clazz.getName());
		}
		//输出拼装后的字符串，得到格式如：[com.foo.Bar,com.foo.Baz]
		return stringJoiner.toString();
	}

	/**
	 * Copy the given {@code Collection} into a {@code Class} array.
	 * <p>
	 *     将给定的Collection对象复制到元素类型为{@code Class}的数组中
	 * </p>
	 * <p>The {@code Collection} must contain {@code Class} elements only.
	 * <p>
	 *     {@code collection}必须仅包含{@code Class}元素
	 * </p>
	 * @param collection the {@code Collection} to copy 要复制的Conllection对象
	 * @return the {@code Class} array 元素类型为{@code Class}的数组对象
	 * @since 3.1
	 * @see StringUtils#toStringArray
	 */
	public static Class<?>[] toClassArray(Collection<Class<?>> collection) {
		//collection对象的元素复制到元素类型为{@code Class}的数组中,传入的数组长度小于collection的长度时，
		//会自动进行扩容
		return (!CollectionUtils.isEmpty(collection) ? collection.toArray(EMPTY_CLASS_ARRAY) : EMPTY_CLASS_ARRAY);
	}

	/**
	 * Return all interfaces that the given instance implements as an array,
	 * including ones implemented by superclasses.
	 * <p>
	 *     以数组形式返回给定实现的所有接口，包括超类实现的接口
	 * </p>
	 * @param instance the instance to analyze for interfaces 分析接口的实例
	 * @return all interfaces that the given instance implements as an array
	 * 			 以数组形式返回给定实现的所有接口
	 */
	public static Class<?>[] getAllInterfaces(Object instance) {
		//如果instance为null，抛出异常
		Assert.notNull(instance, "Instance must not be null");
		return getAllInterfacesForClass(instance.getClass());
	}

	/**
	 * Return all interfaces that the given class implements as an array,
	 * including ones implemented by superclasses.
	 * <p>
	 *      以数组形式返回给定实现的所有接口，包括超类实现的接口
	 * </p>
	 * <p>If the class itself is an interface, it gets returned as sole interface.
	 * <p>
	 *     如果类本身就是接口，则将其作为唯一的接口返回。
	 * </p>
	 * @param clazz the class to analyze for interfaces 分析接口的实例
	 * @return all interfaces that the given object implements as an array
	 * 				以数组形式返回给定实现的所有接口
	 */
	public static Class<?>[] getAllInterfacesForClass(Class<?> clazz) {
		return getAllInterfacesForClass(clazz, null);
	}

	/**
	 * Return all interfaces that the given class implements as an array,
	 * including ones implemented by superclasses.
	 * <p>
	 *      以数组形式返回给定实现的所有接口，包括超类实现的接口
	 * </p>
	 * <p>If the class itself is an interface, it gets returned as sole interface.
	 * <p>
	 *     如果类本身就是接口，则将其作为唯一的接口返回。
	 * </p>
	 * @param clazz the class to analyze for interfaces 分析接口的实例
	 * @param classLoader the ClassLoader that the interfaces need to be visible in
	 * (may be {@code null} when accepting all declared interfaces)
	 *                    接口需要在其中可见的类型加载器(在接收所有声明的接口时可以为null)
	 * @return all interfaces that the given object implements as an array
	 * 				以数组形式返回给定实现的所有接口
	 */
	public static Class<?>[] getAllInterfacesForClass(Class<?> clazz, @Nullable ClassLoader classLoader) {
		//调用getAllInterfacesForClassAsSet(clazz, classLoader)方法，收集clazz的所有对classLoader可见的接口，
		//包括超类实现的接口,得到Set对象，将对Set转换成Class对象数组
		return toClassArray(getAllInterfacesForClassAsSet(clazz, classLoader));
	}

	/**
	 * Return all interfaces that the given instance implements as a Set,
	 * including ones implemented by superclasses.
	 * <p>
	 *      以数组形式返回给定实现的所有接口，包括超类实现的接口
	 * </p>
	 * @param instance the instance to analyze for interfaces 分析接口的实例
	 * @return all interfaces that the given instance implements as a Set
	 * 			以数组形式返回给定实现的所有接口
	 */
	public static Set<Class<?>> getAllInterfacesAsSet(Object instance) {
		//如果instance为null，抛出异常
		Assert.notNull(instance, "Instance must not be null");
		//获取instance的Class对象，再获取该Class对象的所有接口，包括超类实现的接口，以Set形式返回出去
		return getAllInterfacesForClassAsSet(instance.getClass());
	}



	/**
	 * Return all interfaces that the given class implements as a Set,
	 * including ones implemented by superclasses.
	 * <p>
	 *      以Set形式返回给定实现的所有接口，包括超类实现的接口
	 * </p>
	 * <p>If the class itself is an interface, it gets returned as sole interface.
	 * <p>
	 *     如果类本身就是接口，则将其作为唯一的接口返回。
	 * </p>
	 * @param clazz the class to analyze for interfaces 分析接口的实例
	 * @return all interfaces that the given object implements as a Set
	 * 			以Set形式返回给定实现的所有接口
	 */
	public static Set<Class<?>> getAllInterfacesForClassAsSet(Class<?> clazz) {
		return getAllInterfacesForClassAsSet(clazz, null);
	}

	/**
	 * Return all interfaces that the given class implements as a Set,
	 * including ones implemented by superclasses.
	 * <p>
	 *       以Set形式返回给定实现的所有接口，包括超类实现的接口
	 * </p>
	 * <p>If the class itself is an interface, it gets returned as sole interface.
	 * <p>
	 *     如果类本身就是接口，则将其作为唯一的接口返回。
	 * </p>
	 * @param clazz the class to analyze for interfaces 分析接口的实例
	 * @param classLoader the ClassLoader that the interfaces need to be visible in
	 * (may be {@code null} when accepting all declared interfaces)
	 *                    接口需要在其中可见的类型加载器(在接收所有声明的接口时可以为null)
	 * @return all interfaces that the given object implements as a Set
	 * 				以Set形式返回给定实现的所有接口
	 */
	public static Set<Class<?>> getAllInterfacesForClassAsSet(Class<?> clazz, @Nullable ClassLoader classLoader) {
		//如果clazz为null，抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//如果clazz是接口 且 clazz在给定的classLoader中是可见的
		if (clazz.isInterface() && isVisible(clazz, classLoader)) {
			//返回一个不可变只包含clazz的Set对象
			return Collections.singleton(clazz);
		}
		//LinkedHashSet:是一种按照插入顺序维护集合中条目的链表。这允许对集合进行插入顺序迭代。
		//也就是说，当使用迭代器循环遍历LinkedHashSet时，元素将按插入顺序返回。
		//然后将哈希码用作存储与键相关联的数据的索引。将键转换为其哈希码是自动执行的。
		//定义一个用于存储接口类对象的Set
		Set<Class<?>> interfaces = new LinkedHashSet<>();
		//将clazz赋值给current,表示当前类对象
		Class<?> current = clazz;
		//遍历，只要current不为null
		while (current != null) {
			//获取current的所有接口
			Class<?>[] ifcs = current.getInterfaces();
			//遍历ifcs
			for (Class<?> ifc : ifcs) {
				//如果ifc在给定的classLoader中是可见的
				if (isVisible(ifc, classLoader)) {
					//将ifc添加到interfaces中
					interfaces.add(ifc);
				}
			}
			//获取current的父类重新赋值给current
			current = current.getSuperclass();
		}
		//返回存储接口类对象的Set
		return interfaces;
	}

	/**
	 * Create a composite interface Class for the given interfaces,
	 * implementing the given interfaces in one single Class.
	 * <p>
	 *     为给定的接口创建一个复合接口类，在一个类中实现给定接口
	 * </p>
	 * <p>This implementation builds a JDK proxy class for the given interfaces.
	 * <p>
	 *    这个实现是给定的接口构建的一个JDK代理类
	 * </p>
	 * @param interfaces the interfaces to merge 合并的接口
	 * @param classLoader the ClassLoader to create the composite Class in 用于创建复合类的类加载器
	 * @return the merged interface as Class  已合并接口的类
	 * @throws IllegalArgumentException if the specified interfaces expose
	 * conflicting method signatures (or a similar constraint is violated)
	 * 			如果指定接口公开冲突的方法签名（或者违反了类似的约束)
	 * @see java.lang.reflect.Proxy#getProxyClass
	 */
	@SuppressWarnings("deprecation")  // on JDK 9
	public static Class<?> createCompositeInterface(Class<?>[] interfaces, @Nullable ClassLoader classLoader) {
		//如果interface为null,抛出异常
		Assert.notEmpty(interfaces, "Interface array must not be empty");
		//创建动态代理类，得到的Class对象可以通过获取InvocationHandler类型的构造函数进行实例化代理对象
		//详细用法：https://blog.csdn.net/u012516166/article/details/76033249
		return Proxy.getProxyClass(classLoader, interfaces);
	}

	/**
	 * Determine the common ancestor of the given classes, if any.
	 * <p>
	 *     确定给定类的共同祖先类(如果有)
	 * </p>
	 * <p>
	 *     introspect:中文意思：内省，在计算机科学中，内省是指计算机程序在运行时（Run time）
	 *     检查对象（Object）类型的一种能力，通常也可以称作运行时类型检查。不应该将内省和反射混淆。
	 *     相对于内省，反射更进一步，是指计算机程序在运行时（Run time）可以访问、检测和修改它本身
	 *     状态或行为的一种能力。
	 * </p>
	 * @param clazz1 the class to introspect 内省的类
	 * @param clazz2 the other class to introspect 另一个内省的类
	 * @return the common ancestor (i.e. common superclass, one interface
	 * extending the other), or {@code null} if none found. If any of the
	 * given classes is {@code null}, the other class will be returned.
	 * 		共同祖先(即,公共超类，一个接口扩展另一个接口)，或者{@code null}如果没有找到.
	 *		如果给定的任何一个类为{@code null}，另一个类将会被返回
	 * @since 3.2.6
	 */
	@Nullable
	public static Class<?> determineCommonAncestor(@Nullable Class<?> clazz1, @Nullable Class<?> clazz2) {
		//如果clazz1为null
		if (clazz1 == null) {
			//直接返回clazz2
			return clazz2;
		}
		//如果clazz2为null
		if (clazz2 == null) {
			//直接返回clazz1
			return clazz1;
		}
		//如果clazz1是clazz2的父类或是其本身
		if (clazz1.isAssignableFrom(clazz2)) {
			//直接返回clazz1
			return clazz1;
		}
		//如果clazz2是clazz1的父类或是其本身
		if (clazz2.isAssignableFrom(clazz1)) {
			//直接返回clazz2
			return clazz2;
		}
		//将clazz1赋值给ancestor，表示当前祖先类
		Class<?> ancestor = clazz1;
		//循环，只要ancestor不是clazz2的父类或是其本身
		do {
			//获取ancestor的父类重新赋值给ancestor
			ancestor = ancestor.getSuperclass();
			//如果ancesstor为null或者ancestor为Object
			if (ancestor == null || Object.class == ancestor) {
				//直接返回null
				return null;
			}
		}
		while (!ancestor.isAssignableFrom(clazz2));
		//返回当前祖先类
		return ancestor;
	}

	/**
	 * Determine whether the given interface is a common Java language interface:
	 * {@link Serializable}, {@link Externalizable}, {@link Closeable}, {@link AutoCloseable},
	 * {@link Cloneable}, {@link Comparable} - all of which can be ignored when looking
	 * for 'primary' user-level interfaces. Common characteristics: no service-level
	 * operations, no bean property methods, no default methods.
	 * <p>
	 *     确定给定的接口是否是一个公共Java语言接口：
	 *     {@link Serializable}, {@link Externalizable}, {@link Closeable}, {@link AutoCloseable},
	 * 	   {@link Cloneable}, {@link Comparable} - 当寻找'primary'用户级别的接口是，所有这些都可以忽略。
	 * 	   公共特定：没有服务级别操作，没有bean属性方法，没有默认方法
	 * </p>
	 * @param ifc the interface to check
	 * @since 5.0.3
	 */
	public static boolean isJavaLanguageInterface(Class<?> ifc) {
		//如果ifc在javaLanguageInterfaces中存在
		return javaLanguageInterfaces.contains(ifc);
	}

	/**
	 * Determine if the supplied class is an <em>inner class</em>,
	 * i.e. a non-static member of an enclosing class.
	 * <p>
	 *     确定所提供的类是否为内部类，即一个封闭类的非静态成员
	 * </p>
	 * @return {@code true} if the supplied class is an inner class {@code true}如果所提供的类是一个内部类
	 * @since 5.0.5
	 * @see Class#isMemberClass()
	 */
	public static boolean isInnerClass(Class<?> clazz) {
		//如果clazz是成员类 且 clazz没有被静态修饰符修饰
		return (clazz.isMemberClass() && !Modifier.isStatic(clazz.getModifiers()));
	}

	/**
	 * Determine if the supplied {@link Class} is a JVM-generated implementation
	 * class for a lambda expression or method reference.
	 * <p>This method makes a best-effort attempt at determining this, based on
	 * checks that work on modern, mainstream JVMs.
	 * @param clazz the class to check
	 * @return {@code true} if the class is a lambda implementation class
	 * @since 5.3.19
	 */
	public static boolean isLambdaClass(Class<?> clazz) {
		return (clazz.isSynthetic() && (clazz.getSuperclass() == Object.class) &&
				(clazz.getInterfaces().length > 0) && clazz.getName().contains("$$Lambda"));
	}

	/**
	 * Check whether the given object is a CGLIB proxy.
	 * <p>
	 *     判断给定的对象是否是一个CGLIB代理类
	 * </p>
	 * @param object the object to check 检查对象
	 * @see #isCglibProxyClass(Class)
	 * @see org.springframework.aop.support.AopUtils#isCglibProxy(Object)
	 * @deprecated as of 5.2, in favor of custom (possibly narrower) checks
	 * 				从5.2开始，建议使用自定义(可能更窄)的检查
	 */
	@Deprecated
	public static boolean isCglibProxy(Object object) {
		//获取object的类对象，判断类对象是否是CGLIB代理类
		return isCglibProxyClass(object.getClass());
	}

	/**
	 * Check whether the specified class is a CGLIB-generated class.
	 * <p>
	 *     检查指定类是否是一个CGLIB生成的类
	 * </p>
	 * @param clazz the class to check 检查的类
	 * @see #isCglibProxyClassName(String)
	 * @deprecated as of 5.2, in favor of custom (possibly narrower) checks
	 * 				从5.2开始，建议使用自定义(可能更窄)的检查
	 */
	@Deprecated
	public static boolean isCglibProxyClass(@Nullable Class<?> clazz) {
		//如果clazz不为null 且 获取clazz的全类名是CGLIB代理类的全类类名时，返回true；否则返回false
		return (clazz != null && isCglibProxyClassName(clazz.getName()));
	}

	/**
	 * Check whether the specified class name is a CGLIB-generated class.
	 * <p>
	 *     检查指定全类名是否是一个CGLIB生成的类
	 * </p>
	 * @param className the class name to check 检查全类名
	 * @deprecated as of 5.2, in favor of custom (possibly narrower) checks
	 * 				从5.2开始，建议使用自定义(可能更窄)的检查
	 */
	@Deprecated
	public static boolean isCglibProxyClassName(@Nullable String className) {
		//如果className不为null 且 className包含'$$'的字符串，返回true
		return (className != null && className.contains(CGLIB_CLASS_SEPARATOR));
	}

	/**
	 * Return the user-defined class for the given instance: usually simply
	 * the class of the given instance, but the original class in case of a
	 * CGLIB-generated subclass.
	 * <p>
	 *   返回给定实例的用户定义类：通常给定的实例是简单的类，但是在一个CGLIB生成的子类
	 *   情况下，则是原始类
	 * </p>
	 * @param instance the instance to check 检查对象
	 * @return the user-defined class 用户定义类
	 */
	public static Class<?> getUserClass(Object instance) {
		//如果instance为null，抛出异常
		Assert.notNull(instance, "Instance must not be null");
		//获取instance的类对象，得到用户定义的类，但是在一个CGLIB生成的子类
		//情况下，则是原始类
		return getUserClass(instance.getClass());
	}

	/**
	 * <p>如果clazz是CGLIB生成的子类，则返回该子类的父类，否则直接返回要检查的类</p>
	 * Return the user-defined class for the given class: usually simply the given
	 * class, but the original class in case of a CGLIB-generated subclass.
	 * <p>
	 *     返回给定实例的用户定义类：通常给定的实例是简单的类，但是在一个CGLIB生成的子类
	 * 	   情况下，则是原始类
	 * </p>
	 * @param clazz the class to check 检查类
	 * @return the user-defined class 用户定义的
	 */
	public static Class<?> getUserClass(Class<?> clazz) {
		//如果clazz的全类名包含'$$'字符串，表示它有可能是GGLIB生成的子类
		if (clazz.getName().contains(CGLIB_CLASS_SEPARATOR)) {
			//获取clazz的父类
			Class<?> superclass = clazz.getSuperclass();
			//如果superclass不为null 且 superclass不是Object
			if (superclass != null && superclass != Object.class) {
				//直接返回父类
				return superclass;
			}
		}
		//直接返回检查类
		return clazz;
	}

	/**
	 * Return a descriptive name for the given object's type: usually simply
	 * the class name, but component type class name + "[]" for arrays,
	 * and an appended list of implemented interfaces for JDK proxies.
	 * <p>
	 *     返回给定对象的类的一个描述名：通常是简单类名，但是数组就是
	 *     元素类型名+'[]'，和一个JDK代理的已实现接口的附加列表
	 * </p>
	 * @param value the value to introspect 内省对象
	 * @return the qualified name of the class 类的合格名称
	 */
	@Nullable
	public static String getDescriptiveType(@Nullable Object value) {
		//如果value为null
		if (value == null) {
			//直接返回null
			return null;
		}
		//获取value的类
		Class<?> clazz = value.getClass();
		//如果clazz是JDK代理类
		if (Proxy.isProxyClass(clazz)) {
			//拼装前缀 ，类全名+'implementing'
			String prefix = clazz.getName() + " implementing ";
			//新建一个StringJoiner对象，每个添加的字符串都会加入prefix前缀和''后缀，
			// 并使用','分割每个添加进来字符串
			StringJoiner result = new StringJoiner(",", prefix, "");
			//遍历clazz的所有接口
			for (Class<?> ifc : clazz.getInterfaces()) {
				//将ifc的类全名添加到result中
				result.add(ifc.getName());
			}
			//获取拼装好的字符串
			return result.toString();
		}
		else {
			//getTypeName:数组->java.lang.String[],成员内部类->lang.reflect.AAA$BBB,匿名内部类->lang.reflect.AAA$4
			//	普通类->lang.reflect.AAA,基本数据类型-> int
			return clazz.getTypeName();
		}
	}

	/**
	 * Check whether the given class matches the user-specified type name.
	 * 检查给定类是否匹配用户指定全类名
	 * @param clazz the class to check 检查类
	 * @param typeName the type name to match 匹配的全类名
	 */
	public static boolean matchesTypeName(Class<?> clazz, @Nullable String typeName) {
		//getTypeName:数组->java.lang.String[],成员内部类->lang.reflect.AAA$BBB,匿名内部类->lang.reflect.AAA$4
		//	普通类->lang.reflect.AAA,基本数据类型-> int
		//如果 typeName不为null 且 (获取clazz的类型名 等于 typName 或者 获取clazz的简单类名 等于 typeName)
		//			返回true；否则返回false
		return (typeName != null &&
				(typeName.equals(clazz.getTypeName()) || typeName.equals(clazz.getSimpleName())));
	}


	/**
	 * Get the class name without the qualified package name.
	 * <p>
	 *		获取没有合格包名的类名
	 * </p>
	 * @param className the className to get the short name for 获取短名称的类名
	 * @return the class name of the class without the package name 没有合格包名的类的类名
	 * @throws IllegalArgumentException if the className is empty 如果{@code className}为空
	 */
	public static String getShortName(String className) {
		//如果className为null或者是空字符串，抛出异常
		Assert.hasLength(className, "Class name must not be empty");
		//获取className最后一个'.'的索引位置
		int lastDotIndex = className.lastIndexOf(PACKAGE_SEPARATOR);
		//获取className的'$$'的索引位置
		int nameEndIndex = className.indexOf(CGLIB_CLASS_SEPARATOR);
		//如果没有找到'$$'的索引位置
		if (nameEndIndex == -1) {
			//将className的长度赋值给nameEndIndex
			nameEndIndex = className.length();
		}
		//截取出'.'到nameEndIndex之间的字符串
		String shortName = className.substring(lastDotIndex + 1, nameEndIndex);
		//将shortName的'$'替换成'.'重新赋值给shortName,表示将 AAA$BBB ->AAA.BBB
		shortName = shortName.replace(NESTED_CLASS_SEPARATOR, PACKAGE_SEPARATOR);
		return shortName;
	}

	/**
	 * <p>获取type的短类名，即去掉了包名，且对内部类名会将$替换成.</p>
	 * Get the class name without the qualified package name.
	 * <p>
	 *     获取没有合格包名的类名
	 * </p>
	 * @param clazz the class to get the short name for  获取短名称的类名
	 * @return the class name of the class without the package name 没有合格包名的类的类名
	 */
	public static String getShortName(Class<?> clazz) {
		//获取clazz合格的全类名，再获取没有合格包名的类名
		return getShortName(getQualifiedName(clazz));
	}

	/**
	 * Return the short string name of a Java class in uncapitalized JavaBeans
	 * property format. Strips the outer class name in case of an inner class.
	 * <p>
	 *		以没有大写的JavaBeans属性格式返回java类的短字符串名.如果是内部类,
	 *		则去除外部类名称
	 * </p>
	 * @param clazz the class 类对象
	 * @return the short name rendered in a standard JavaBeans property format
	 * 			以标准JavaBeans属性格式呈现短名称
	 * @see java.beans.Introspector#decapitalize(String)
	 */
	public static String getShortNameAsProperty(Class<?> clazz) {
		//获取没有合格包名的类名
		String shortName = getShortName(clazz);
		//获取shorName的最后一个'.'的索引位置
		int dotIndex = shortName.lastIndexOf(PACKAGE_SEPARATOR);
		//如果找到'.'的索引位置，就截取出'.'后面的字符串,重新赋值给shortName
		shortName = (dotIndex != -1 ? shortName.substring(dotIndex + 1) : shortName);
		//StringUtils.uncapitalizeAsProperty:获得一个字符串并将它转换成普通 java 变量名称大写形式的实用工具方法。
		// 这通常意味着将首字符从大写转换成小写，但在（不平常的）特殊情况下，当有多个字符且
		// 第一个和第二个字符都是大写字符时，不执行任何操作。
		// 因此 "STRINGS" 变成 "STRINGS"，"STing" 变成 "STing"，"Sting" 变成 "sting",但 "string" 仍然是 "string"。
		return StringUtils.uncapitalizeAsProperty(shortName);
	}

	/**
	 * Determine the name of the class file, relative to the containing
	 * package: e.g. "String.class"
	 * <p>
	 *     确定类文件的名，相对于包含的包：即'String.class'
	 * </p>
	 * @param clazz the class 类
	 * @return the file name of the ".class" file '.class'文件的文件名
	 */
	public static String getClassFileName(Class<?> clazz) {
		//如果clazz为null，抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//获取clazz的全类名
		String className = clazz.getName();
		//获取clazz的最后一个'.'索引位置
		int lastDotIndex = className.lastIndexOf(PACKAGE_SEPARATOR);
		//截取出clazz的'.'后面的字符串加上'.class'，然后返回出去
		return className.substring(lastDotIndex + 1) + CLASS_FILE_SUFFIX;
	}



	/**
	 * Determine the name of the package of the given class,
	 * e.g. "java.lang" for the {@code java.lang.String} class.
	 * <p>
	 *     确定给定类的包名,例如{@code java.lang.String}类的'java.lang'
	 * </p>
	 * @param clazz the class 类
	 * @return the package name, or the empty String if the class
	 * is defined in the default package
	 * 			包名，如果默认包中定义了类，则返回空字符串
	 */
	public static String getPackageName(Class<?> clazz) {
		//如果clazz为null，抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//获取clazz的全类名，截取出包名
		return getPackageName(clazz.getName());
	}

	/**
	 * Determine the name of the package of the given fully-qualified class name,
	 * e.g. "java.lang" for the {@code java.lang.String} class name.
	 * <p>
	 *     确定给定类的包名,例如{@code java.lang.String}类的'java.lang'
	 * </p>
	 * @param fqClassName the fully-qualified class name 完全合格的类名
	 * @return the package name, or the empty String if the class
	 * is defined in the default package
	 * 			包名，如果默认包中定义了类，则返回空字符串
	 */
	public static String getPackageName(String fqClassName) {
		//如果fqClassName为null，抛出异常
		Assert.notNull(fqClassName, "Class name must not be null");
		//获取fqClassName的最后一个'.'索引位置
		int lastDotIndex = fqClassName.lastIndexOf(PACKAGE_SEPARATOR);
		//如果找到'.'的索引位置，就截取出fqClassName中lastDotIndex后面的字符串;否则返回空字符串
		return (lastDotIndex != -1 ? fqClassName.substring(0, lastDotIndex) : "");
	}

	/**
	 * Return the qualified name of the given class: usually simply
	 * the class name, but component type class name + "[]" for arrays.
	 * <p>
	 *     获取没有合格包名的类名:通常是简单类名，但是数组就是
	 * 		   元素类型名+'[]'，和一个JDK代理的已实现接口的附加列表
	 * </p>
	 * @param clazz the class 类
	 * @return the qualified name of the class 类的合格名
	 */
	public static String getQualifiedName(Class<?> clazz) {
		//如果clazz为null，抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//getTypeName:数组->java.lang.String[],成员内部类->lang.reflect.AAA$BBB,匿名内部类->lang.reflect.AAA$4
		//	普通类->lang.reflect.AAA,基本数据类型-> int
		return clazz.getTypeName();
	}

	/**
	 * Return the qualified name of the given method, consisting of
	 * fully qualified interface/class name + "." + method name.
	 * <p>
	 * 		返回给定方法对象的合格名称，有完全合格的接口名/类名+'.'+方法名组成
	 * </p>
	 * @param method the method 方法对象
	 * @return the qualified name of the method 方法对象的合格名称
	 */
	public static String getQualifiedMethodName(Method method) {
		//以定义method的类作为拼装方法合格名称的类名
		return getQualifiedMethodName(method, null);
	}

	/**
	 * Return the qualified name of the given method, consisting of
	 * fully qualified interface/class name + "." + method name.
	 * <p>
	 *     返回给定方法对象的合格名称，有完全合格的接口名/类名+'.'+方法名组成
	 * </p>
	 * @param method the method 方法对象
	 * @param clazz the clazz that the method is being invoked on
	 * (may be {@code null} to indicate the method's declaring class)
	 *              被调用该方法的类对象(可以为{@code null},以表示该方法的声明类
	 * @return the qualified name of the method
	 * 			方法对象的合格名称
	 * @since 4.3.4
	 */
	public static String getQualifiedMethodName(Method method, @Nullable Class<?> clazz) {
		//如果method为null，抛出异常
		Assert.notNull(method, "Method must not be null");
		//如果clazz不为null，就用clazz,否则使用声明method的类对象，然后取出类对象的全类名，加上'.',再加上方法名，最后返回出去
		return (clazz != null ? clazz : method.getDeclaringClass()).getName() + '.' + method.getName();
	}

	/**
	 * Determine whether the given class has a public constructor with the given signature.
	 * <p>
	 *     确定给定的类是否具有代用给定签名的公共构造函数
	 * </p>
	 * <p>Essentially translates {@code NoSuchMethodException} to "false".
	 * <p>
	 *     本质上将{@code NoSuchMethodException}转换为false
	 * </p>
	 * @param clazz the clazz to analyze 分析的类
	 * @param paramTypes the parameter types of the method 方法的参数类型数组
	 * @return whether the class has a corresponding constructor
	 * 				是否具有代用给定签名的公共构造函数
	 * @see Class#getMethod
	 */
	public static boolean hasConstructor(Class<?> clazz, Class<?>... paramTypes) {
		//获取paramTypes的clazz的构造函数对象,如果不为null返回true；否则返回false
		return (getConstructorIfAvailable(clazz, paramTypes) != null);
	}

	/**
	 * Determine whether the given class has a public constructor with the given signature,
	 * and return it if available (else return {@code null}).
	 * <p>
	 *     确定给定的类是否具有代用给定签名的公共构造函数,和返回是否可用(否则返回{@code null})
	 * </p>
	 * <p>Essentially translates {@code NoSuchMethodException} to {@code null}.
	 * <p>
	 *     本质上将{@code NoSuchMethodException}转换为false
	 * </p>
	 * @param clazz the clazz to analyze 分析的类
	 * @param paramTypes the parameter types of the method 方法的参数类型数组
	 * @return the constructor, or {@code null} if not found 构造函数，如果没有找到则为{@code null}
	 * @see Class#getConstructor
	 */
	@Nullable
	public static <T> Constructor<T> getConstructorIfAvailable(Class<T> clazz, Class<?>... paramTypes) {
		//如果clazz为null，抛出异常
		Assert.notNull(clazz, "Class must not be null");
		try {
			//获取paramTypes的clazz的构造函数对象
			return clazz.getConstructor(paramTypes);
		}
		catch (NoSuchMethodException ex) {
			//捕捉没有该构造函数的异常，返回null
			return null;
		}
	}

	/**
	 * Determine whether the given class has a public method with the given signature.
	 * @param clazz the clazz to analyze
	 * @param method the method to look for
	 * @return whether the class has a corresponding method
	 * @since 5.2.3
	 */
	public static boolean hasMethod(Class<?> clazz, Method method) {
		Assert.notNull(clazz, "Class must not be null");
		Assert.notNull(method, "Method must not be null");
		if (clazz == method.getDeclaringClass()) {
			return true;
		}
		String methodName = method.getName();
		Class<?>[] paramTypes = method.getParameterTypes();
		return getMethodOrNull(clazz, methodName, paramTypes) != null;
	}

	/**
	 * Determine whether the given class has a public method with the given signature.
	 * <p>Essentially translates {@code NoSuchMethodException} to "false".
	 * <p>
	 *     确定给定的类是否具有带有给定签名的公共方法
	 * </p>
	 * <p>
	 *     本质上将{@code NoSuchMethodException}转换为false
	 * </p>
	 * @param clazz the clazz to analyze 分析的类
	 * @param methodName the name of the method 方法名
	 * @param paramTypes the parameter types of the method 方法的参数类型数组
	 * @return whether the class has a corresponding method 该类是否具有对应的方法
	 * @see Class#getMethod
	 */
	public static boolean hasMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
		return (getMethodIfAvailable(clazz, methodName, paramTypes) != null);
	}

	/**
	 * Determine whether the given class has a public method with the given signature,
	 * and return it if available (else throws an {@code IllegalStateException}).
	 * <p>
	 *     确定给定的类是否具有带有给定签名的公共方法，并返回(如果可用)(否则抛出{@code IllegalStateException}异常).
	 * </p>
	 * <p>In case of any signature specified, only returns the method if there is a
	 * unique candidate, i.e. a single public method with the specified name.
	 * <p>
	 *     如果指定了任何前面，则仅存在唯一候选者的情况下才返回该方法，即具有指定名称的单个
	 *     public方法
	 * </p>
	 * <p>Essentially translates {@code NoSuchMethodException} to {@code IllegalStateException}.
	 * <p>
	 *     本质上将{@code NoSuchMethodException}转换为 {@code IllegalStateException}.
	 * </p>
	 * @param clazz the clazz to analyze 分析的类
	 * @param methodName the name of the method 方法名
	 * @param paramTypes the parameter types of the method
	 * (may be {@code null} to indicate any signature)
	 *                   	方法的参数类型数组(可以为null表示任何签名)
	 * @return the method (never {@code null}) 方法对象(永远返回null)
	 * @throws IllegalStateException if the method has not been found 如果没有找到该方法
	 * @see Class#getMethod
	 */
	public static Method getMethod(Class<?> clazz, String methodName, @Nullable Class<?>... paramTypes) {
		//如果clazz为null，抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//如果methodName为null，抛出异常
		Assert.notNull(methodName, "Method name must not be null");
		//如果paramType不为null
		if (paramTypes != null) {
			try {
				//获取clazz的方法名为methodName和参数类型为paramType的方法对象
				return clazz.getMethod(methodName, paramTypes);
			}
			catch (NoSuchMethodException ex) {
				//捕捉没有找到对应方法的异常，抛出IllegalStateException
				throw new IllegalStateException("Expected method not found: " + ex);
			}
		}
		else {
			//获取在clazz中匹配methodName的候选方法
			Set<Method> candidates = findMethodCandidatesByName(clazz, methodName);
			//如果只匹配到一个方法
			if (candidates.size() == 1) {
				//取出candidates的迭代器，获取第一个元素并返回出去
				return candidates.iterator().next();
			}
			//如果没有匹配到方法，直接抛出IllegalStateException
			else if (candidates.isEmpty()) {
				throw new IllegalStateException("Expected method not found: " + clazz.getName() + '.' + methodName);
			}
			//如果匹配到多个方法对象，直接抛出IllegalStateException
			else {
				throw new IllegalStateException("No unique method found: " + clazz.getName() + '.' + methodName);
			}
		}
	}

	/**
	 * Determine whether the given class has a public method with the given signature,
	 * and return it if available (else return {@code null}).
	 * <p>
	 *       确定给定的类是否具有带有给定签名的公共方法，并返回(如果可用)(否则返回{@code null}).
	 * </p>
	 * <p>In case of any signature specified, only returns the method if there is a
	 * unique candidate, i.e. a single public method with the specified name.
	 * <p>
	 *		如果指定了任何前面，则仅存在唯一候选者的情况下才返回该方法，即具有指定名称的单个
	 *		public方法
	 * </p>
	 * <p>Essentially translates {@code NoSuchMethodException} to {@code null}.
	 * <p>
	 *     本质上将{@code NoSuchMethodException}转换为false
	 * </p>
	 * @param clazz the clazz to analyze 分析的类
	 * @param methodName the name of the method 方法名
	 * @param paramTypes the parameter types of the method
	 * (may be {@code null} to indicate any signature)
	 *                   方法的参数类型数组(可以为null表示任何签名)
	 * @return the method, or {@code null} if not found 方法对象，如果没有找到就为null
	 * @see Class#getMethod
	 */
	@Nullable
	public static Method getMethodIfAvailable(Class<?> clazz, String methodName, @Nullable Class<?>... paramTypes) {
		//如果clazz为null,抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//如果methodName为null，抛出异常
		Assert.notNull(methodName, "Method name must not be null");
		//如果paramTypes不为null
		if (paramTypes != null) {
			try {
				//获取clazz的方法名为methodName和参数类型为paramType的方法对象
				return clazz.getMethod(methodName, paramTypes);
			}
			catch (NoSuchMethodException ex) {
				//捕捉没有找到对应方法的异常，返回null
				return null;
			}
		}
		else {
			//获取在clazz中匹配methodName的候选方法
			Set<Method> candidates = findMethodCandidatesByName(clazz, methodName);
			//如果只匹配到一个方法
			if (candidates.size() == 1) {
				//取出candidates的迭代器，获取第一个元素并返回出去
				return candidates.iterator().next();
			}
			//如果匹配到多个方法对象，直接返回null
			return null;
		}
	}

	/**
	 * Return the number of methods with a given name (with any argument types),
	 * for the given class and/or its superclasses. Includes non-public methods.
	 * <p>
	 *     返回给定名称（带有任何参数类型)的方法数量，从给定的类和/或其父类。包含非public方法
	 * </p>
	 * @param clazz	the clazz to check 检查类
	 * @param methodName the name of the method 方法名
	 * @return the number of methods with the given name 给定名的方法数量
	 */
	public static int getMethodCountForName(Class<?> clazz, String methodName) {
		//如果clazz为null，抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//如果methodName为null，抛出异常
		Assert.notNull(methodName, "Method name must not be null");
		//初始化方法数量为0
		int count = 0;
		//获取clazz的所有方法
		Method[] declaredMethods = clazz.getDeclaredMethods();
		//遍历clazz的所有方法
		for (Method method : declaredMethods) {
			//如果method的方法名等于methodName
			if (methodName.equals(method.getName())) {
				//计数器+1
				count++;
			}
		}
		//获取clazz的所有接口
		Class<?>[] ifcs = clazz.getInterfaces();
		//遍历clazz的所有接口
		for (Class<?> ifc : ifcs) {
			//递归方法，取得ifc中匹配methodNamde的方法数量，再加到count上
			count += getMethodCountForName(ifc, methodName);
		}
		//如果clazz的父类不为null
		if (clazz.getSuperclass() != null) {
			//递归方法，取得clazz的父类中匹配methodName的方法数量，再加到count上
			count += getMethodCountForName(clazz.getSuperclass(), methodName);
		}
		return count;
	}

	/**
	 * Does the given class or one of its superclasses at least have one or more
	 * methods with the supplied name (with any argument types)?
	 * Includes non-public methods.
	 * <p>
	 *     给定的类或其超类之一是否至少具有一个或多个带有提供的名称（带有任何参数类型)的方法？
	 *     包含非public方法
	 * </p>
	 * @param clazz	the clazz to check 检查的类
	 * @param methodName the name of the method 方法名
	 * @return whether there is at least one method with the given name
	 * 			是否至少有一个使用给定名称的方法
	 */
	public static boolean hasAtLeastOneMethodWithName(Class<?> clazz, String methodName) {
		//如果clazz为null,抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//如果methodName为null,抛出异常
		Assert.notNull(methodName, "Method name must not be null");
		//获取clazz的所有方法(包括非public方法)
		Method[] declaredMethods = clazz.getDeclaredMethods();
		//遍历clazz的所有方法
		for (Method method : declaredMethods) {
			//如果method的名称 等于 methodName
			if (method.getName().equals(methodName)) {
				//返回true
				return true;
			}
		}
		//获取clazz的所有接口
		Class<?>[] ifcs = clazz.getInterfaces();
		//遍历clazz的所有接口
		for (Class<?> ifc : ifcs) {
			//递归方法,如果ifc至少有一个使用methodName的方法
			if (hasAtLeastOneMethodWithName(ifc, methodName)) {
				//直接返回true
				return true;
			}
		}
		//获取clazz的父类，如有不为null 且 父类中至少有一个使用methodName的方法，就返回true；否则返回false
		return (clazz.getSuperclass() != null && hasAtLeastOneMethodWithName(clazz.getSuperclass(), methodName));
	}

	/**
	 * Given a method, which may come from an interface, and a target class used
	 * in the current reflective invocation, find the corresponding target method
	 * if there is one. E.g. the method may be {@code IFoo.bar()} and the
	 * target class may be {@code DefaultFoo}. In this case, the method may be
	 * {@code DefaultFoo.bar()}. This enables attributes on that method to be found.
	 * <p>
	 *     给定一个可能来自接口以及在当前反射调用中使用的目标类的方法,找到相应的目标方法
	 *     (如果有),例如,该方法可以是{@code IFoo.bar()}，目标类可以使{@code DefaultFoo}.
	 *     在这种情况下，该方法可以使{@code DefaultFoo.bar()}.这样可以找到该方法的属性
	 * </p>
	 * <p><b>NOTE:</b> In contrast to {@link org.springframework.aop.support.AopUtils#getMostSpecificMethod},
	 * this method does <i>not</i> resolve Java 5 bridge methods automatically.
	 * Call {@link org.springframework.core.BridgeMethodResolver#findBridgedMethod}
	 * if bridge method resolution is desirable (e.g. for obtaining metadata from
	 * the original method definition).
	 * <p>
	 *     注意：与之相反的 {@link org.springframework.aop.support.AopUtils#getMostSpecificMethod},
	 *     该方法不会自动解析Java 5的桥接方法.
	 *     如果桥接方法解析度是可取的(例如用于从原始方法定义中获取元数据),调用
	 * 	   {@link org.springframework.core.BridgeMethodResolver#findBridgedMethod}
	 * </p>
	 * <p><b>NOTE:</b> Since Spring 3.1.1, if Java security settings disallow reflective
	 * access (e.g. calls to {@code Class#getDeclaredMethods} etc, this implementation
	 * will fall back to returning the originally provided method.
	 * <p>
	 *     注意：从Spring3.1.1开始，如果Java安全设置不允许反射访问(例如,调用
	 *      {@code Class#getDeclaredMethods}等等，该实现将退回到最初提供的方法。
	 * </p>
	 * @param method the method to be invoked, which may come from an interface
	 *               	要调用的方法，可能来自接口
	 * @param targetClass the target class for the current invocation
	 * (may be {@code null} or may not even implement the method)
	 *                    当前调用的目标类(可以为null,或者设置可能没有实现该方法)
	 * @return the specific target method, or the original method if the
	 * {@code targetClass} does not implement it
	 * 					指定目标方法，或者如果{@code targetClass}未实现,则为原始方法
	 * @see #getInterfaceMethodIfPossible
	 */
	public static Method getMostSpecificMethod(Method method, @Nullable Class<?> targetClass) {
		//如果targetClass不为null 且 targetClass 不是声明method的类 且 method在targetClass中可重写
		if (targetClass != null && targetClass != method.getDeclaringClass() && isOverridable(method, targetClass)) {
			try {
				//如果method是public方法
				if (Modifier.isPublic(method.getModifiers())) {
					try {
						//获取targetClass的方法名为method的方法名和参数类型数组为method的参数类型数组的方法对象
						return targetClass.getMethod(method.getName(), method.getParameterTypes());
					}
					catch (NoSuchMethodException ex) {
						//捕捉没有找到方法异常,直接返回给定的方法
						return method;
					}
				}
				else {
					//反射获取targetClass的方法名为method的方法名和参数类型数组为method的参数类型数组的方法对象
					Method specificMethod =
							ReflectionUtils.findMethod(targetClass, method.getName(), method.getParameterTypes());
					//如果specifiMethod不为null,就返回specifiMetho,否则返回给定的方法
					return (specificMethod != null ? specificMethod : method);
				}
			}
			catch (SecurityException ex) {
				// Security settings are disallowing reflective access; fall back to 'method' below.
				// 安全性设置不允许反射式访问；回到下面的'method'
			}
		}
		//直接返回给定的方法
		return method;
	}


	/**
	 * Determine a corresponding interface method for the given method handle, if possible.
	 * <p>This is particularly useful for arriving at a public exported type on Jigsaw
	 * which can be reflectively invoked without an illegal access warning.
	 * @param method the method to be invoked, potentially from an implementation class
	 * @return the corresponding interface method, or the original method if none found
	 * @since 5.1
	 * @deprecated in favor of {@link #getInterfaceMethodIfPossible(Method, Class)}
	 */
	@Deprecated
	public static Method getInterfaceMethodIfPossible(Method method) {
		return getInterfaceMethodIfPossible(method, null);
	}

	/**
	 * <p>获取method相应的接口方法对象，如果找不到，则返回原始方法</p>
	 * Determine a corresponding interface method for the given method handle, if possible.
	 * <p>
	 *     如果可能，为给定的方法句柄确定相应的接口方法
	 * </p>
	 * <p>This is particularly useful for arriving at a public exported type on Jigsaw
	 * which can be reflectively invoked without an illegal access warning.
	 * <p>
	 *     这对于在Jigsaw上实现公共导出类特别有用，可以在没有非法访问警告的情况下进行
	 *     反射调用
	 * </p>
	 * <p>
	 *     Jigsaw是万维网协会（W3C）的一个Web服务器，它是为展示新的Web协议和其他特征而设计的。
	 *     Jigsaw是用Java编程语言写的，并且它是一个开源的软件。你可以在Unix或Windows 2000上从W3C网址
	 *     下载Jigsaw。
	 * 　　虽然Jigsaw是设计为一个展示平台而非全能的Web服务器，但W3C说，它的性能和CERN服务器没有区别，
	 * 	   并且它提供了许多一般的Web服务器支持，例如代理服务器的能力，虚拟主机，以及公用网关接口的支持。
	 * 	   Jigsaw还可用于传送用个人主页以及JSP脚本语言建的或修改的页面。
	 * </p>
	 * @param method the method to be invoked, potentially from an implementation class
	 *               	可能从实现类中调用的方法
	 * @return the corresponding interface method, or the original method if none found
	 * 				相应的接口方法，如果找不到，则返回原始方法
	 * @since 5.1
	 * @see #getMostSpecificMethod
	 */
	public static Method getInterfaceMethodIfPossible(Method method, @Nullable Class<?> targetClass) {

		if (!Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass().isInterface()) {
			return method;
		}
		// Try cached version of method in its declaring class
		Method result = interfaceMethodCache.computeIfAbsent(method,
				key -> findInterfaceMethodIfPossible(key, key.getDeclaringClass(), Object.class));
		if (result == method && targetClass != null) {
			// No interface method found yet -> try given target class (possibly a subclass of the
			// declaring class, late-binding a base class method to a subclass-declared interface:
			// see e.g. HashMap.HashIterator.hasNext)
			result = findInterfaceMethodIfPossible(method, targetClass, method.getDeclaringClass());
		}
		return result;
	}

	private static Method findInterfaceMethodIfPossible(Method method, Class<?> startClass, Class<?> endClass) {
		Class<?> current = startClass;
		while (current != null && current != endClass) {
			Class<?>[] ifcs = current.getInterfaces();
			for (Class<?> ifc : ifcs) {
				try {
					return ifc.getMethod(method.getName(), method.getParameterTypes());
				}
				catch (NoSuchMethodException ex) {
					// ignore
				}
			}
			current = current.getSuperclass();
		}
		return method;
	}

	/**
	 * Determine whether the given method is declared by the user or at least pointing to
	 * a user-declared method.
	 * <p>
	 *     确定给定的方法是有用户声明的，或者只是指向用户声明方法
	 * </p>
	 * <p>Checks {@link Method#isSynthetic()} (for implementation methods) as well as the
	 * {@code GroovyObject} interface (for interface methods; on an implementation class,
	 * implementations of the {@code GroovyObject} methods will be marked as synthetic anyway).
	 * Note that, despite being synthetic, bridge methods ({@link Method#isBridge()}) are considered
	 * as user-level methods since they are eventually pointing to a user-declared generic method.
	 * <p>
	 *     检查 {@link Method#isSynthetic()}(用于实现的方法)以及{@code GrooyObject}接口(用于接口方法;
	 *     在实现类上，无论如何,{@code GroovyObject}方法的实现将被标记为合成。主要，尽管是合成的，桥接方法
	 *    ({@link Method#isBridge()}) 被认为用户级的方法，因为他们最终指向用户声明的通用方法
	 * </p>
	 * @param method the method to check 检查方法
	 * @return {@code true} if the method can be considered as user-declared; [@code false} otherwise
	 * 			{@code true} 如果方法可以被视为用户声明的方法；否则返回{@code false}
	 */
	public static boolean isUserLevelMethod(Method method) {
		//如果method为null，抛出异常
		Assert.notNull(method, "Method must not be null");
		// 桥接方法：就是说一个子类在继承（或实现）一个父类（或接口）的泛型方法时，
		// 		在子类中明确指定了泛型类型，那么在编译时编译器会自动生成桥接方法（当然还有其他情况会生成桥接方法，
		// 		这里只是列举了其中一种情况）。https://blog.csdn.net/qq_32647893/article/details/81071336
		// isSynthetic:判断此字段是否为编译期间生成的合成字段，如果是则返回true; 否则返回false。
		// 			https://www.jianshu.com/p/3f75221437a3
		//如果method是桥接方法 或者 (method不是编译期间生成的合成字段 且 method不是GroovyObject的方法时返回true
		//	否则返回false
		return (method.isBridge() || (!method.isSynthetic() && !isGroovyObjectMethod(method)));
	}

	/**
	 * 判断是否GroovyObject的方法，GroovyObject表示Groovy语言
	 * @param method 方法对象
	 * @return 是否GroovyObject
	 */
	private static boolean isGroovyObjectMethod(Method method) {
		//获取声明method的类的全类名 ，如果等于'groovy.lang.GroovyObject'就返回true；否则返回false
		return method.getDeclaringClass().getName().equals("groovy.lang.GroovyObject");
	}

	/**
	 * Determine whether the given method is overridable in the given target class.
	 * <p>
	 *     确定给定的方法在给定目标类中是否可重写
	 * </p>
	 * @param method the method to check 检查的方法
	 * @param targetClass the target class to check against 要检查的目标类
	 */
	private static boolean isOverridable(Method method, @Nullable Class<?> targetClass) {
		//如果method是private
		if (Modifier.isPrivate(method.getModifiers())) {
			//直接返回false
			return false;
		}
		//如果method是public 或 method是否Protected
		if (Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers())) {
			//直接返回true
			return true;
		}
		//如果targetClass为null 或者 获取声明method的类的包名 等于 目标类的包名是返回true，否则返回false
		return (targetClass == null ||
				getPackageName(method.getDeclaringClass()).equals(getPackageName(targetClass)));
	}

	/**
	 * Return a public static method of a class.
	 * <p>
	 *     返回类的公共静态方法
	 * </p>
	 * @param clazz the class which defines the method 定义方法的类
	 * @param methodName the static method name 静态方法名
	 * @param args the parameter types to the method 方法的参数类型数组
	 * @return the static method, or {@code null} if no static method was found
	 * 			静态方法，如果是未找到非静态方法时，为null
	 * @throws IllegalArgumentException if the method name is blank or the clazz is null
	 * 										如果方法名称为空白或者clazz为null
	 */
	@Nullable
	public static Method getStaticMethod(Class<?> clazz, String methodName, Class<?>... args) {
		//如果clazz为null,抛出异常
		Assert.notNull(clazz, "Class must not be null");
		//如果methodName为null，抛出异常
		Assert.notNull(methodName, "Method name must not be null");
		try {
			//获取clazz的方法名为methodName和参数类型为args的方法对象
			Method method = clazz.getMethod(methodName, args);
			//如果method是静态方法，就返回出去；否则返回null
			return Modifier.isStatic(method.getModifiers()) ? method : null;
		}
		catch (NoSuchMethodException ex) {
			//捕捉未找到方法异常，直接返回null
			return null;
		}
	}


	@Nullable
	private static Method getMethodOrNull(Class<?> clazz, String methodName, Class<?>[] paramTypes) {
		try {
			return clazz.getMethod(methodName, paramTypes);
		}
		catch (NoSuchMethodException ex) {
			return null;
		}
	}

	/**
	 * 按方法名查询候选方法
	 * @param clazz 分析的类
	 * @param methodName 方法名
	 * @return 在clazz中匹配methodName的方法对象的Set集合
	 */
	private static Set<Method> findMethodCandidatesByName(Class<?> clazz, String methodName) {
		//定义一个初始容量为1专门用于存储匹配上methodName的Method对象的Set集合
		Set<Method> candidates = new HashSet<>(1);
		//获取clazz的所有方法
		Method[] methods = clazz.getMethods();
		//遍历方法对象
		for (Method method : methods) {
			//获取method的方法名，如果等于methodName
			if (methodName.equals(method.getName())) {
				//将method对象添加到candidate
				candidates.add(method);
			}
		}
		return candidates;
	}

}
