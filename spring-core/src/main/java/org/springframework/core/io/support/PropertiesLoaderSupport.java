/*
 * Copyright 2002-2020 the original author or authors.
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
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DefaultPropertiesPersister;
import org.springframework.util.PropertiesPersister;

/**
 * Base class for JavaBean-style components that need to load properties
 * from one or more resources. Supports local properties as well, with
 * configurable overriding.
 *
 * @author Juergen Hoeller
 * @since 1.2.2
 */
public abstract class PropertiesLoaderSupport {

	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	protected Properties[] localProperties;

	protected boolean localOverride = false;

	@Nullable
	private Resource[] locations;

	private boolean ignoreResourceNotFound = false;

	@Nullable
	private String fileEncoding;

	private PropertiesPersister propertiesPersister = DefaultPropertiesPersister.INSTANCE;


	/**
	 * Set local properties, e.g. via the "props" tag in XML bean definitions.
	 * These can be considered defaults, to be overridden by properties
	 * loaded from files.
	 */
	public void setProperties(Properties properties) {
		this.localProperties = new Properties[] {properties};
	}

	/**
	 * Set local properties, e.g. via the "props" tag in XML bean definitions,
	 * allowing for merging multiple properties sets into one.
	 */
	public void setPropertiesArray(Properties... propertiesArray) {
		this.localProperties = propertiesArray;
	}

	/**
	 * Set a location of a properties file to be loaded.
	 * <p>Can point to a classic properties file or to an XML file
	 * that follows JDK 1.5's properties XML format.
	 */
	public void setLocation(Resource location) {
		this.locations = new Resource[] {location};
	}

	/**
	 * Set locations of properties files to be loaded.
	 * <p>Can point to classic properties files or to XML files
	 * that follow JDK 1.5's properties XML format.
	 * <p>Note: Properties defined in later files will override
	 * properties defined earlier files, in case of overlapping keys.
	 * Hence, make sure that the most specific files are the last
	 * ones in the given list of locations.
	 */
	public void setLocations(Resource... locations) {
		this.locations = locations;
	}

	/**
	 * Set whether local properties override properties from files.
	 * <p>Default is "false": Properties from files override local defaults.
	 * Can be switched to "true" to let local properties override defaults
	 * from files.
	 */
	public void setLocalOverride(boolean localOverride) {
		this.localOverride = localOverride;
	}

	/**
	 * Set if failure to find the property resource should be ignored.
	 * <p>"true" is appropriate if the properties file is completely optional.
	 * Default is "false".
	 */
	public void setIgnoreResourceNotFound(boolean ignoreResourceNotFound) {
		this.ignoreResourceNotFound = ignoreResourceNotFound;
	}

	/**
	 * Set the encoding to use for parsing properties files.
	 * <p>Default is none, using the {@code java.util.Properties}
	 * default encoding.
	 * <p>Only applies to classic properties files, not to XML files.
	 * @see org.springframework.util.PropertiesPersister#load
	 */
	public void setFileEncoding(String encoding) {
		this.fileEncoding = encoding;
	}

	/**
	 * Set the PropertiesPersister to use for parsing properties files.
	 * The default is {@code DefaultPropertiesPersister}.
	 * @see DefaultPropertiesPersister#INSTANCE
	 */
	public void setPropertiesPersister(@Nullable PropertiesPersister propertiesPersister) {
		this.propertiesPersister =
				(propertiesPersister != null ? propertiesPersister : DefaultPropertiesPersister.INSTANCE);
	}


	/**
	 * Return a merged Properties instance containing both the
	 * loaded properties and properties set on this FactoryBean.
	 * 返回包含此工厂中设置的外部属性文件的属性合并之后的属性集合实例
	 */
	protected Properties mergeProperties() throws IOException {
		//合并属性集合
		Properties result = new Properties();
		//localOverride属性默认false，因此不会覆盖
		if (this.localOverride) {
			// Load properties from file upfront, to let local properties override.
			loadProperties(result);
		}
		//如果本地属性不为null
		if (this.localProperties != null) {
			//那么合并属性，因此相同key的属性将会覆盖
			for (Properties localProp : this.localProperties) {
				CollectionUtils.mergePropertiesIntoMap(localProp, result);
			}
		}
		//加载本地配置的属性文件中的属性
		if (!this.localOverride) {
			//从本地文件文件加载属性，以允许这些属性重写。
			loadProperties(result);
		}

		return result;
	}


	/**
	 * Load properties into the given instance.
	 * 将本地文件中的属性加载到给定的实例中。
	 * @param props the Properties instance to load into
	 * @throws IOException in case of I/O errors
	 * @see #setLocations
	 */
	protected void loadProperties(Properties props) throws IOException {
		//遍历加载进来的文件Resource资源
		if (this.locations != null) {
			for (Resource location : this.locations) {
				if (logger.isTraceEnabled()) {
					logger.trace("Loading properties file from " + location);
				}
				try {
					//将Resource资源中的属性键值对加载到给定的props集合中
					PropertiesLoaderUtils.fillProperties(
							props, new EncodedResource(location, this.fileEncoding), this.propertiesPersister);
				} catch (FileNotFoundException | UnknownHostException ex) {
					if (this.ignoreResourceNotFound) {
						if (logger.isDebugEnabled()) {
							logger.debug("Properties resource not found: " + ex.getMessage());
						}
					} else {
						throw ex;
					}
				}
			}
		}
	}

}
