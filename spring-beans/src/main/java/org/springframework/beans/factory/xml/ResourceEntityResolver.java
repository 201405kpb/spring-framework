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

package org.springframework.beans.factory.xml;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.ResourceUtils;

/**
 * {@code EntityResolver} implementation that tries to resolve entity references
 * through a {@link org.springframework.core.io.ResourceLoader} (usually,
 * relative to the resource base of an {@code ApplicationContext}), if applicable.
 * Extends {@link DelegatingEntityResolver} to also provide DTD and XSD lookup.
 *
 * <p>Allows to use standard XML entities to include XML snippets into an
 * application context definition, for example to split a large XML file
 * into various modules. The include paths can be relative to the
 * application context's resource base as usual, instead of relative
 * to the JVM working directory (the XML parser's default).
 *
 * <p>Note: In addition to relative paths, every URL that specifies a
 * file in the current system root, i.e. the JVM working directory,
 * will be interpreted relative to the application context too.
 *
 * @author Juergen Hoeller
 * @since 31.07.2003
 * @see org.springframework.core.io.ResourceLoader
 * @see org.springframework.context.ApplicationContext
 */
public class ResourceEntityResolver extends DelegatingEntityResolver {

	private static final Log logger = LogFactory.getLog(ResourceEntityResolver.class);

	private final ResourceLoader resourceLoader;


	/**
	 * Create a ResourceEntityResolver for the specified ResourceLoader
	 * (usually, an ApplicationContext).
	 * @param resourceLoader the ResourceLoader (or ApplicationContext)
	 * to load XML entity includes with
	 */
	public ResourceEntityResolver(ResourceLoader resourceLoader) {
		super(resourceLoader.getClassLoader());
		this.resourceLoader = resourceLoader;
	}


	@Override
	@Nullable
	public InputSource resolveEntity(@Nullable String publicId, @Nullable String systemId)
			throws SAXException, IOException {
		// 调用父类的方法，进行解析
		InputSource source = super.resolveEntity(publicId, systemId);
		// 解析失败，resourceLoader 进行解析
		if (source == null && systemId != null) {
			// 获得 resourcePath ，即 Resource 资源地址
			String resourcePath = null;
			try {
				String decodedSystemId = URLDecoder.decode(systemId, StandardCharsets.UTF_8);
				String givenUrl = ResourceUtils.toURL(decodedSystemId).toString();
				// 解析文件资源的相对路径（相对于系统根路径）
				String systemRootUrl = new File("").toURI().toURL().toString();
				// Try relative to resource base if currently in system root.
				if (givenUrl.startsWith(systemRootUrl)) {
					resourcePath = givenUrl.substring(systemRootUrl.length());
				}
			}
			catch (Exception ex) {
				// Typically a MalformedURLException or AccessControlException.
				if (logger.isDebugEnabled()) {
					logger.debug("Could not resolve XML entity [" + systemId + "] against system root URL", ex);
				}
				// No URL (or no resolvable URL) -> try relative to resource base.
				resourcePath = systemId;
			}
			if (resourcePath != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Trying to locate XML entity [" + systemId + "] as resource [" + resourcePath + "]");
				}
				// 获得 Resource 资源
				Resource resource = this.resourceLoader.getResource(resourcePath);
				// 创建 InputSource 对象
				source = new InputSource(resource.getInputStream());
				// 设置 publicId 和 systemId 属性
				source.setPublicId(publicId);
				source.setSystemId(systemId);
				if (logger.isDebugEnabled()) {
					logger.debug("Found XML entity [" + systemId + "]: " + resource);
				}
			}
			else if (systemId.endsWith(DTD_SUFFIX) || systemId.endsWith(XSD_SUFFIX)) {
				source = resolveSchemaEntity(publicId, systemId);
			}
		}

		return source;
	}

	/**
	 * A fallback method for {@link #resolveEntity(String, String)} that is used when a
	 * "schema" entity (DTD or XSD) cannot be resolved as a local resource. The default
	 * behavior is to perform remote resolution over HTTPS.
	 * <p>Subclasses can override this method to change the default behavior.
	 * <ul>
	 * <li>Return {@code null} to fall back to the parser's
	 * {@linkplain org.xml.sax.EntityResolver#resolveEntity(String, String) default behavior}.</li>
	 * <li>Throw an exception to prevent remote resolution of the DTD or XSD.</li>
	 * </ul>
	 * @param publicId the public identifier of the external entity being referenced,
	 * or null if none was supplied
	 * @param systemId the system identifier of the external entity being referenced,
	 * representing the URL of the DTD or XSD
	 * @return an InputSource object describing the new input source, or null to request
	 * that the parser open a regular URI connection to the system identifier
	 * @since 6.0.4
	 */
	@Nullable
	protected InputSource resolveSchemaEntity(@Nullable String publicId, String systemId) {
		InputSource source;
		// External dtd/xsd lookup via https even for canonical http declaration
		String url = systemId;
		if (url.startsWith("http:")) {
			url = "https:" + url.substring(5);
		}
		if (logger.isWarnEnabled()) {
			logger.warn("DTD/XSD XML entity [" + systemId + "] not found, falling back to remote https resolution");
		}
		try {
			source = new InputSource(ResourceUtils.toURL(url).openStream());
			source.setPublicId(publicId);
			source.setSystemId(systemId);
		}
		catch (IOException ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Could not resolve XML entity [" + systemId + "] through URL [" + url + "]", ex);
			}
			// Fall back to the parser's default behavior.
			source = null;
		}
		return source;
	}

}
