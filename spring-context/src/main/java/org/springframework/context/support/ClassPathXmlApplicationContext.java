/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.context.support;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Standalone XML application context, taking the context definition files
 * from the class path, interpreting plain paths as class path resource names
 * that include the package path (e.g. "mypackage/myresource.txt"). Useful for
 * test harnesses as well as for application contexts embedded within JARs.
 *
 * 默认就是加载项目的 classpath 路径下面的配置文件，可以不加上“classpath:”前缀；
 * 但是如果要使用绝对路径，就必须需要加上“file:”前缀
 *
 * <p>The config location defaults can be overridden via {@link #getConfigLocations},
 * Config locations can either denote concrete files like "/myfiles/context.xml"
 * or Ant-style patterns like "/myfiles/*-context.xml" (see the
 * {@link org.springframework.util.AntPathMatcher} javadoc for pattern details).
 *
 * <p>Note: In case of multiple config locations, later bean definitions will
 * override ones defined in earlier loaded files. This can be leveraged to
 * deliberately override certain bean definitions via an extra XML file.
 *
 * <p><b>This is a simple, one-stop shop convenience ApplicationContext.
 * Consider using the {@link GenericApplicationContext} class in combination
 * with an {@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}
 * for more flexible context setup.</b>
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #getResource
 * @see #getResourceByPath
 * @see GenericApplicationContext
 */
public class ClassPathXmlApplicationContext extends AbstractXmlApplicationContext {

	@Nullable
	private Resource[] configResources;


	/**
	 * Create a new ClassPathXmlApplicationContext for bean-style configuration.
	 * 使用给定的父级创建新的ClassPathXmlApplicationContext，从给定的XML文件加载配置。
	 * @see #setConfigLocation
	 * @see #setConfigLocations
	 * @see #afterPropertiesSet()
	 */
	public ClassPathXmlApplicationContext() {
	}

	/**
	 * Create a new ClassPathXmlApplicationContext for bean-style configuration.
	 * 使用给定的父级创建新的ClassPathXmlApplicationContext，从给定的XML文件加载配置。
	 * @param parent the parent context
	 * @see #setConfigLocation
	 * @see #setConfigLocations
	 * @see #afterPropertiesSet()
	 */
	public ClassPathXmlApplicationContext(ApplicationContext parent) {
		super(parent);
	}

	/**
	 * Create a new ClassPathXmlApplicationContext, loading the definitions
	 * from the given XML file and automatically refreshing the context.
	 * 使用给定的父级创建新的ClassPathXmlApplicationContext，从给定的XML文件加载配置。
	 * @param configLocation resource location
	 * @throws BeansException if context creation failed
	 */
	public ClassPathXmlApplicationContext(String configLocation) throws BeansException {
		this(new String[] {configLocation}, true, null);
	}

	/**
	 * Create a new ClassPathXmlApplicationContext, loading the definitions
	 * from the given XML files and automatically refreshing the context.
	 * 使用给定的父级创建新的ClassPathXmlApplicationContext，从给定的XML文件加载配置。
	 * @param configLocations array of resource locations
	 * @throws BeansException if context creation failed
	 */
	public ClassPathXmlApplicationContext(String... configLocations) throws BeansException {
		this(configLocations, true, null);
	}

	/**
	 * Create a new ClassPathXmlApplicationContext with the given parent,
	 * loading the definitions from the given XML files and automatically
	 * refreshing the context.
	 * 使用给定的父级创建新的ClassPathXmlApplicationContext，从给定的XML文件加载配置。
	 * @param configLocations array of resource locations
	 * @param parent the parent context
	 * @throws BeansException if context creation failed
	 */
	public ClassPathXmlApplicationContext(String[] configLocations, @Nullable ApplicationContext parent)
			throws BeansException {

		this(configLocations, true, parent);
	}

	/**
	 * Create a new ClassPathXmlApplicationContext, loading the definitions
	 * from the given XML files.
	 * 使用给定的父级创建新的ClassPathXmlApplicationContext，从给定的XML文件加载配置。
	 * @param configLocations array of resource locations
	 * @param refresh whether to automatically refresh the context,
	 * loading all bean definitions and creating all singletons.
	 * Alternatively, call refresh manually after further configuring the context.
	 * @throws BeansException if context creation failed
	 * @see #refresh()
	 */
	public ClassPathXmlApplicationContext(String[] configLocations, boolean refresh) throws BeansException {
		this(configLocations, refresh, null);
	}

	/**
	 * Create a new ClassPathXmlApplicationContext with the given parent,
	 * loading the definitions from the given XML files.
	 * 使用给定的父级创建新的ClassPathXmlApplicationContext，从给定的XML文件加载配置。
	 * @param configLocations array of resource locations 资源文件位置数组
	 * @param refresh whether to automatically refresh the context,
	 * loading all bean definitions and creating all singletons.
	 * Alternatively, call refresh manually after further configuring the context.
	 * 是否自动刷新上下文、加载所有 bean 定义和创建所有单例bean。
	 * @param parent the parent context 父上下文容器，默认null
	 * @throws BeansException if context creation failed 如果上下文创建失败,抛出 BeansException 异常
	 * @see #refresh()
	 */
	public ClassPathXmlApplicationContext(
			String[] configLocations, boolean refresh, @Nullable ApplicationContext parent)
			throws BeansException {
		// 设置父上下文容器，默认为null
		super(parent);
		// 根据提供的路径，处理成配置文件数组(以分号、逗号、空格、tab、换行符分割)
		setConfigLocations(configLocations);
		//是否刷新上下文
		if (refresh) {
			//刷新上下文，这是核心方法
			refresh();
		}
	}


	/**
	 * Create a new ClassPathXmlApplicationContext, loading the definitions
	 * from the given XML file and automatically refreshing the context.
	 * 创建新的ClassPathXmlApplicationContext，从给定的XML文件加载定义并自动刷新上下文。
	 * <p>This is a convenience method to load class path resources relative to a
	 * given Class. For full flexibility, consider using a GenericApplicationContext
	 * with an XmlBeanDefinitionReader and a ClassPathResource argument.
	 * @param path relative (or absolute) path within the class path
	 *             类路径中的相对（或绝对）路径数组
	 * @param clazz the class to load resources with (basis for the given paths)
	 *              要加载资源的类（基于给定路径）
	 * @throws BeansException if context creation failed
	 * 如果上下文创建失败,抛出 BeansException 异常
	 * @see org.springframework.core.io.ClassPathResource#ClassPathResource(String, Class)
	 * @see org.springframework.context.support.GenericApplicationContext
	 * @see org.springframework.beans.factory.xml.XmlBeanDefinitionReader
	 */
	public ClassPathXmlApplicationContext(String path, Class<?> clazz) throws BeansException {
		this(new String[] {path}, clazz);
	}

	/**
	 * Create a new ClassPathXmlApplicationContext, loading the definitions
	 * from the given XML files and automatically refreshing the context.
	 * 创建新的ClassPathXmlApplicationContext，从给定的XML文件加载定义并自动刷新上下文。
	 * @param paths array of relative (or absolute) paths within the class path
	 *              类路径中的相对（或绝对）路径数组
	 * @param clazz the class to load resources with (basis for the given paths)
	 *              要加载资源的类（基于给定路径）
	 * @throws BeansException if context creation failed
	 * 如果上下文创建失败,抛出 BeansException 异常
	 * @see org.springframework.core.io.ClassPathResource#ClassPathResource(String, Class)
	 * @see org.springframework.context.support.GenericApplicationContext
	 * @see org.springframework.beans.factory.xml.XmlBeanDefinitionReader
	 */
	public ClassPathXmlApplicationContext(String[] paths, Class<?> clazz) throws BeansException {
		this(paths, clazz, null);
	}

	/**
	 * Create a new ClassPathXmlApplicationContext with the given parent,
	 * loading the definitions from the given XML files and automatically
	 * refreshing the context.
	 * 使用给定的父级创建新的ClassPathXmlApplicationContext，从给定的XML文件加载定义并自动刷新上下文。
	 * @param paths array of relative (or absolute) paths within the class path
	 *              类路径中的相对（或绝对）路径数组
	 * @param clazz the class to load resources with (basis for the given paths)
	 *              要加载资源的类（基于给定路径）
	 * @param parent the parent context
	 *               父容器
	 * @throws BeansException if context creation failed
	 * 如果上下文创建失败,抛出 BeansException 异常
	 * @see org.springframework.core.io.ClassPathResource#ClassPathResource(String, Class)
	 * @see org.springframework.context.support.GenericApplicationContext
	 * @see org.springframework.beans.factory.xml.XmlBeanDefinitionReader
	 */
	public ClassPathXmlApplicationContext(String[] paths, Class<?> clazz, @Nullable ApplicationContext parent)
			throws BeansException {

		super(parent);
		Assert.notNull(paths, "Path array must not be null");
		Assert.notNull(clazz, "Class argument must not be null");
		this.configResources = new Resource[paths.length];
		for (int i = 0; i < paths.length; i++) {
			this.configResources[i] = new ClassPathResource(paths[i], clazz);
		}
		refresh();
	}


	@Override
	@Nullable
	protected Resource[] getConfigResources() {
		return this.configResources;
	}

}
