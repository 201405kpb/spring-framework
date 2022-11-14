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

package org.springframework.context.annotation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.support.PropertySourceDescriptor;
import org.springframework.core.io.support.PropertySourceFactory;
import org.springframework.core.io.support.PropertySourceProcessor;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Registry of {@link PropertySource} processed on configuration classes.
 *
 * @author Stephane Nicoll
 * @since 6.0
 * @see PropertySourceDescriptor
 */
class PropertySourceRegistry {

	private final PropertySourceProcessor propertySourceProcessor;

	private final List<PropertySourceDescriptor> descriptors;

	public PropertySourceRegistry(PropertySourceProcessor propertySourceProcessor) {
		this.propertySourceProcessor = propertySourceProcessor;
		this.descriptors = new ArrayList<>();
	}

	public List<PropertySourceDescriptor> getDescriptors() {
		return Collections.unmodifiableList(this.descriptors);
	}

	/**
	 * Process the given <code>@PropertySource</code> annotation metadata.
	 * 处理给定的@PropertySource注解元数据
	 * @param propertySource metadata for the <code>@PropertySource</code> annotation found
	 * @throws IOException if loading a property source failed
	 */
	void processPropertySource(AnnotationAttributes propertySource) throws IOException {
		//获取name属性，表示属性源的名称
		String name = propertySource.getString("name");
		if (!StringUtils.hasLength(name)) {
			name = null;
		}
		//获取encoding属性，表示编码字符集
		String encoding = propertySource.getString("encoding");
		if (!StringUtils.hasLength(encoding)) {
			encoding = null;
		}
		//获取本地配置文件的路径字符串数组，一个@PropertySource注解可以引入多个属性配置文件
		String[] locations = propertySource.getStringArray("value");
		//断言只有有一个文件路径
		Assert.isTrue(locations.length > 0, "At least one @PropertySource(value) location is required");
		//获取ignoreResourceNotFound属性的值，表示是否允许配置文件找不到
		boolean ignoreResourceNotFound = propertySource.getBoolean("ignoreResourceNotFound");

		//获取factory属性的值，也就是PropertySourceFactory的class，用来创建属性源，默认值就是PropertySourceFactory.class
		Class<? extends PropertySourceFactory> factoryClass = propertySource.getClass("factory");
		//获取PropertySourceFactory，用于创建属性源工厂
		Class<? extends PropertySourceFactory> factorClassToUse =
				(factoryClass != PropertySourceFactory.class ? factoryClass : null);
		PropertySourceDescriptor descriptor = new PropertySourceDescriptor(Arrays.asList(locations), ignoreResourceNotFound, name,
				factorClassToUse, encoding);
		this.propertySourceProcessor.processPropertySource(descriptor);
		this.descriptors.add(descriptor);
	}

}
