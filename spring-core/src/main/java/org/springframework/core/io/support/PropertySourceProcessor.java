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

package org.springframework.core.io.support;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;

/**
 * Contribute {@link PropertySource property sources} to the {@link Environment}.
 *
 * <p>This class is stateful and merge descriptors with the same name in a
 * single {@link PropertySource} rather than creating dedicated ones.
 *
 * @author Stephane Nicoll
 * @see PropertySourceDescriptor
 * @since 6.0
 */
public class PropertySourceProcessor {

	private static final PropertySourceFactory DEFAULT_PROPERTY_SOURCE_FACTORY = new DefaultPropertySourceFactory();

	private static final Log logger = LogFactory.getLog(PropertySourceProcessor.class);

	private final ConfigurableEnvironment environment;

	private final ResourceLoader resourceLoader;

	private final List<String> propertySourceNames;

	public PropertySourceProcessor(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.propertySourceNames = new ArrayList<>();
	}

	/**
	 * Process the specified {@link PropertySourceDescriptor} against the
	 * environment managed by this instance.
	 *
	 * @param descriptor the descriptor to process
	 * @throws IOException if loading the properties failed
	 */
	public void processPropertySource(PropertySourceDescriptor descriptor) throws IOException {
		String name = descriptor.name();
		String encoding = descriptor.encoding();
		List<String> locations = descriptor.locations();
		Assert.isTrue(locations.size() > 0, "At least one @PropertySource(value) location is required");
		boolean ignoreResourceNotFound = descriptor.ignoreResourceNotFound();
		PropertySourceFactory factory = (descriptor.propertySourceFactory() != null
				? instantiateClass(descriptor.propertySourceFactory())
				: DEFAULT_PROPERTY_SOURCE_FACTORY);
		/*
		 * 遍历本地配置文件的路径字符串数组，依次加载配置文件，添加属性源
		 */
		for (String location : locations) {
			try {
				/*
				 * 通过环境变量，解析路径字符串中的占位符，使用严格模式，遇到没有默认值的无法解析的占位符将抛出IllegalArgumentException异常
				 * 这说明我们指定的@PropertySource注解中的location支持${.. : ..}占位符，但是只会从environment环境变量中查找属性，这一点要注意
				 */
				String resolvedLocation = this.environment.resolveRequiredPlaceholders(location);
				//将配置文件加载成为一个Resource资源
				Resource resource = this.resourceLoader.getResource(resolvedLocation);
				/*
				 * 根据当前name、resource、encoding创建一个属性源，如果没有name那么将会生成默认的name，随后添加属性源
				 */
				addPropertySource(factory.createPropertySource(name, new EncodedResource(resource, encoding)));
			} catch (IllegalArgumentException | FileNotFoundException | UnknownHostException | SocketException ex) {
				// Placeholders not resolvable or resource not found when trying to open it
				if (ignoreResourceNotFound) {
					if (logger.isInfoEnabled()) {
						logger.info("Properties location [" + location + "] not resolvable: " + ex.getMessage());
					}
				} else {
					throw ex;
				}
			}
		}
	}

	/**
	 * 添加属性源到environment环境变量中
	 *
	 * @param propertySource 当前@PropertySource指定的配置文件对应的属性源
	 */
	private void addPropertySource(PropertySource<?> propertySource) {
		//获取属性源的name
		String name = propertySource.getName();
		/*
		 * 获取environment环境变量中的getPropertySources属性源集合，这个属性源集合，我们在IoC容器的最最最开始的setLocations方法中就见过了
		 * 该集合在setLocations方法中就初始化了著名的systemProperties — JVM系统属性属性源以及systemEnvironment - 系统环境属性源
		 * 在根据environment替换占位符的时候，就是从这个属性源集合中依次遍历、查找的，因此systemProperties — JVM系统属性属性源的优先级最高
		 */
		MutablePropertySources propertySources = this.environment.getPropertySources();
		/*
		 * 如果属性源名称集合中已包含该名称name，那么就扩展这个名字的属性源
		 * 因为一个@PropertySource可能指定多个配置文件
		 */
		if (this.propertySourceNames.contains(name)) {
			// We've already added a version, we need to extend it
			//获取该名字的属性源
			PropertySource<?> existing = propertySources.get(name);
			//如果不为null
			if (existing != null) {
				PropertySource<?> newSource = (propertySource instanceof ResourcePropertySource ?
						((ResourcePropertySource) propertySource).withResourceName() : propertySource);
				if (existing instanceof CompositePropertySource) {
					//当前属性源加入到existing属性源集合的开头
					((CompositePropertySource) existing).addFirstPropertySource(newSource);
				} else {
					if (existing instanceof ResourcePropertySource) {
						existing = ((ResourcePropertySource) existing).withResourceName();
					}
					//当前属性源加入到属性源集合的开头
					CompositePropertySource composite = new CompositePropertySource(name);
					composite.addPropertySource(newSource);
					composite.addPropertySource(existing);
					propertySources.replace(name, composite);
				}
				return;
			}
		}

		/*
		 * 到这里，表示属性源名称集合中不包含该名称name
		 * 那么添加一个新name的属性源到环境变量的propertySources属性源集合中，同时将name加入到propertySourceNames集合中
		 * 越后添加的属性源查找的优先级越高，但是低于systemProperties和systemEnvironment这两个系统级别的属性源
		 */

		//如果是第一个加载的@PropertySource注解属性源
		if (this.propertySourceNames.isEmpty()) {
			//那么加入到propertySources属性源集合的尾部，查找的优先级最低
			propertySources.addLast(propertySource);
		}
		//否则
		else {
			//获取最后一个添加的属性源名称
			String firstProcessed = this.propertySourceNames.get(this.propertySourceNames.size() - 1);
			//在propertySources中的名为firstProcessed的属性源的索引处添加该属性源，原索引以及之后的属性源向后移动一位
			//即越后添加的属性源优先级越高
			propertySources.addBefore(firstProcessed, propertySource);
		}
		this.propertySourceNames.add(name);
	}

	private PropertySourceFactory instantiateClass(Class<? extends PropertySourceFactory> type) {
		try {
			Constructor<? extends PropertySourceFactory> constructor = type.getDeclaredConstructor();
			ReflectionUtils.makeAccessible(constructor);
			return constructor.newInstance();
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to instantiate " + type, ex);
		}
	}

}
