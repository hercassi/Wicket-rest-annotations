/**
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.wicketstuff.rest.resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.wicket.Application;
import org.apache.wicket.Session;
import org.apache.wicket.WicketRuntimeException;
import org.apache.wicket.authroles.authorization.strategies.role.IRoleCheckingStrategy;
import org.apache.wicket.authroles.authorization.strategies.role.Roles;
import org.apache.wicket.protocol.http.servlet.ServletWebRequest;
import org.apache.wicket.protocol.http.servlet.ServletWebResponse;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.request.resource.IResource;
import org.apache.wicket.util.collections.MultiMap;
import org.apache.wicket.util.convert.IConverter;
import org.apache.wicket.util.string.StringValue;
import org.wicketstuff.rest.annotations.AuthorizeInvocation;
import org.wicketstuff.rest.annotations.MethodMapping;
import org.wicketstuff.rest.annotations.parameters.CookieParam;
import org.wicketstuff.rest.annotations.parameters.HeaderParam;
import org.wicketstuff.rest.annotations.parameters.JsonBody;
import org.wicketstuff.rest.annotations.parameters.QueryParam;
import org.wicketstuff.rest.exception.MethodInvocationAuthException;
import org.wicketstuff.rest.utils.HttpMethod;
import org.wicketstuff.rest.utils.ReflectionUtils;

/**
 * Base class to build a resource that serves REST requests.
 * 
 * @author andrea del bene
 * 
 */
public abstract class AbstractRestResource<T> implements IResource {
	private MultiMap<String, UrlMappingInfo> mappedMethods = new MultiMap<String, UrlMappingInfo>();
	private final T objSerialDeserial;

	/** Role checking strategy. */
	private final IRoleCheckingStrategy roleCheckingStrategy;

	/**
	 * Constructor with no role-checker
	 * 
	 */
	public AbstractRestResource(T jsonSerialDeserial) {
		this(jsonSerialDeserial, null);
	}

	public AbstractRestResource(T jsonSerialDeserial, IRoleCheckingStrategy roleCheckingStrategy) {
		this.objSerialDeserial = jsonSerialDeserial;
		this.roleCheckingStrategy = roleCheckingStrategy;

		loadAnnotatedMethods();
	}

	/***
	 * Handles a REST request invoking one of the methods annotated with
	 * {@link MethodMapping}. If the annotated method returns a value, this
	 * latter is automatically serialized as a JSON string and written in the
	 * web response.
	 */
	@Override
	public void respond(Attributes attributes) {
		PageParameters pageParameters = attributes.getParameters();
		ServletWebResponse response = (ServletWebResponse) attributes.getResponse();
		HttpMethod httpMethod = getHttpMethod((ServletWebRequest) RequestCycle.get().getRequest());
		int indexedParamCount = pageParameters.getIndexedCount();

		List<UrlMappingInfo> mappedMethodsCandidates = mappedMethods.get(indexedParamCount + "_"
				+ httpMethod.getMethod());

		UrlMappingInfo mappedMethod = null;

		if (indexedParamCount == 0 && mappedMethodsCandidates != null)
			mappedMethod = mappedMethodsCandidates.get(0);
		else
			mappedMethod = selectMostSuitedMethod(mappedMethodsCandidates, pageParameters);

		if (mappedMethod != null) {
			if (!hasAny(mappedMethod.getRoles()))
				throw new MethodInvocationAuthException(
						"User is not allowed to invoke this method!");

			Object result = invokeMappedMethod(mappedMethod, pageParameters);

			if (result != null) {
				serializeObjectToResponse(response, result);
			}
		}
	}

	protected void serializeObjectToResponse(ServletWebResponse response, Object result) {
		response.setContentType("application/json");
		try {
			response.write(serializeObjToString(result, objSerialDeserial));
		} catch (Exception e) {
			throw new RuntimeException("Error serializing object to response", e);
		}
	}

	private UrlMappingInfo selectMostSuitedMethod(List<UrlMappingInfo> mappedMethods,
			PageParameters pageParameters) {
		UrlMappingInfo mappingInfo = null;
		int highestScore = 0;

		if (mappedMethods == null || mappedMethods.size() == 0)
			return null;

		for (UrlMappingInfo mappedMethod : mappedMethods) {
			List<StringValue> segments = mappedMethod.getSegments();
			Method targetMethod = mappedMethod.getMethod();
			Class<?>[] argsClasses = mappedMethod.getNotAnnotatedParams();

			int score = 0;
			int functionParamsIndex = 0;

			for (StringValue segment : segments) {
				int i = segments.indexOf(segment);

				if (segment instanceof VariableSegment
						&& isSegmentCompatible(pageParameters.get(i),
								argsClasses[functionParamsIndex++]))
					score++;
				else if (pageParameters.get(i).equals(segment))
					score += 2;
			}

			if (score > highestScore) {
				highestScore = score;
				mappingInfo = mappedMethod;
			}
		}

		return mappingInfo;
	}

	private boolean isSegmentCompatible(StringValue segment, Class<?> paramClass) {
		try {
			Object convertedObject = toObject(paramClass, segment.toString());
		} catch (Exception e) {
			// segment's value not compatible with paramClass
			return false;
		}

		return true;
	}

	protected abstract String serializeObjToString(Object result, T objSerialDeserial);

	/***
	 * Internal method to load class methods annotated with
	 * {@link MethodMapping}
	 */
	private void loadAnnotatedMethods() {
		Method[] methods = getClass().getDeclaredMethods();
		boolean isUsingAuthAnnot = false;

		for (int i = 0; i < methods.length; i++) {
			Method method = methods[i];
			MethodMapping methodMapped = method.getAnnotation(MethodMapping.class);
			AuthorizeInvocation authorizeInvocation = method
					.getAnnotation(AuthorizeInvocation.class);
			isUsingAuthAnnot = isUsingAuthAnnot || authorizeInvocation != null;

			if (methodMapped != null) {
				String urlPath = methodMapped.value();
				HttpMethod httpMethod = methodMapped.httpMethod();
				UrlMappingInfo urlMappingInfo = new UrlMappingInfo(urlPath, httpMethod, method);

				mappedMethods.addValue(
						urlMappingInfo.getSegmentsCount() + "_" + httpMethod.getMethod(),
						urlMappingInfo);
			}
		}

		if (isUsingAuthAnnot && roleCheckingStrategy == null)
			throw new WicketRuntimeException(
					"Annotation AuthorizeInvocation is used but no roleCheckingStrategy has been provided to the controller.");
	}

	/**
	 * Utility method to extract the request method
	 * 
	 * @param clazz
	 * @param value
	 * @return the HTTP method used for this request
	 * @see HttpMethod
	 */
	public static HttpMethod getHttpMethod(ServletWebRequest request) {
		HttpServletRequest httpRequest = request.getContainerRequest();
		return HttpMethod.toHttpMethod((httpRequest.getMethod()));
	}

	/***
	 * This method invokes one of the resource's method annotated with
	 * {@link MethodMapping}
	 * 
	 * @param mappedMethod
	 *            mapping info of the method
	 * @param pageParameters
	 *            PageParametrs object of the current request
	 * @return the value returned by the invoked method
	 */
	private Object invokeMappedMethod(UrlMappingInfo mappedMethod, PageParameters pageParameters) {

		Method targetMethod = mappedMethod.getMethod();
		Class<?>[] argsClasses = targetMethod.getParameterTypes();
		List parametersValues = new ArrayList();
		Iterator<StringValue> segmentsIterator = mappedMethod.getSegments().iterator();
		Annotation[][] parametersAnnotations = targetMethod.getParameterAnnotations();

		for (int i = 0; i < argsClasses.length; i++) {
			Class<?> argClass = argsClasses[i];
			Object paramValue = null;

			if (ReflectionUtils.isParameterNotAnnotated(i, targetMethod))
				paramValue = extractParameterFromUrl(mappedMethod, pageParameters,
						segmentsIterator, argClass);
			else
				paramValue = extractParameterValue(i, targetMethod, argClass, pageParameters);

			if (paramValue == null)
				throw new WicketRuntimeException("Could not find a value for the " + (i + 1)
						+ "° parameter of controller's function " + targetMethod.getName());
			parametersValues.add(paramValue);
		}

		try {
			return targetMethod.invoke(this, parametersValues.toArray());
		} catch (Exception e) {
			throw new RuntimeException("Error invoking method '" + targetMethod.getName() + "'", e);
		}
	}

	private Object extractParameterValue(int i, Method targetMethod, Class<?> argClass,
			PageParameters pageParameters) {
		Object paramValue = null;
		Annotation[][] parametersAnnotations = targetMethod.getParameterAnnotations();

		if (ReflectionUtils.isParameterAnnotatedWith(i, targetMethod, JsonBody.class))
			paramValue = extractObjectFromBody(argClass);
		else if (ReflectionUtils.isParameterAnnotatedWith(i, targetMethod, QueryParam.class))
			paramValue = extractParameterFromQuery(pageParameters, parametersAnnotations[i],
					argClass);
		else if (ReflectionUtils.isParameterAnnotatedWith(i, targetMethod, HeaderParam.class))
			paramValue = extractParameterFromHeader(parametersAnnotations[i], argClass);
		else if (ReflectionUtils.isParameterAnnotatedWith(i, targetMethod, CookieParam.class))
			paramValue = extractParameterFromCookies(parametersAnnotations[i], argClass);
		
		return paramValue;
	}

	private Object extractParameterFromHeader(Annotation[] parameterAnnotations, Class<?> argClass) {

		HeaderParam headerParam = ReflectionUtils.findAnnotation(parameterAnnotations,
				HeaderParam.class);
		String value = headerParam.value();
		ServletWebRequest webRequest = (ServletWebRequest) RequestCycle.get().getRequest();

		return toObject(argClass, webRequest.getHeader(value));
	}

	private Object extractParameterFromQuery(PageParameters pageParameters,
			Annotation[] parameterAnnotations, Class<?> argClass) {

		QueryParam queryParam = ReflectionUtils.findAnnotation(parameterAnnotations,
				QueryParam.class);
		String value = queryParam.value();

		return toObject(argClass, pageParameters.get(value).toString());
	}

	private Object extractParameterFromCookies(Annotation[] parameterAnnotations, Class<?> argClass) {

		CookieParam cookieParam = ReflectionUtils.findAnnotation(parameterAnnotations,
				CookieParam.class);
		String value = cookieParam.value();
		ServletWebRequest webRequest = (ServletWebRequest) RequestCycle.get().getRequest();

		return toObject(argClass, webRequest.getCookie(value).getValue());
	}
	
	/**
	 * Internal method that tries to extract an instance of the given class from
	 * the request body.
	 * 
	 * @param argClass
	 *            the type we want to extract from request body
	 * @return the extracted object
	 */
	private Object extractObjectFromBody(Class<?> argClass) {
		ServletWebRequest servletRequest = (ServletWebRequest) RequestCycle.get().getRequest();
		HttpServletRequest httpRequest = servletRequest.getContainerRequest();
		try {
			BufferedReader bufReader = httpRequest.getReader();
			StringBuilder builder = new StringBuilder();
			String jsonString;

			while ((jsonString = bufReader.readLine()) != null)
				builder.append(jsonString);

			return deserializeObjFromString(argClass, builder.toString(), objSerialDeserial);
		} catch (IOException e) {
			throw new RuntimeException("Error deserializing object from request", e);
		}
	}

	protected abstract Object deserializeObjFromString(Class<?> argClass, String strValue,
			T objSerialDeserial);

	/***
	 * Extract parameters values from the rest URL
	 * 
	 * @param mappedMethod
	 *            mapping info of the method
	 * @param pageParameters
	 *            PageParametrs object of the current request
	 * @param segmentsIterator
	 *            iterator over the mapped segments
	 * @param argClass
	 *            type of the parameter we want to extract
	 * @return the parameter's value
	 */
	private Object extractParameterFromUrl(UrlMappingInfo mappedMethod,
			PageParameters pageParameters, Iterator<StringValue> segmentsIterator, Class<?> argClass) {
		try {
			StringValue segmentValue = null;

			while (segmentsIterator.hasNext()) {
				StringValue currentSegment = segmentsIterator.next();

				if (currentSegment instanceof VariableSegment) {
					segmentValue = currentSegment;
					break;
				}
			}

			if (segmentValue != null) {
				int indexOf = mappedMethod.getSegments().indexOf(segmentValue);
				StringValue actualValue = pageParameters.get(indexOf);

				return toObject(argClass, actualValue.toString());
			}
			return null;
		} catch (Exception e) {
			throw new RuntimeException("Error retrieving a constructor with a string parameter.", e);
		}
	}

	/**
	 * Utility method to convert string values to the corresponding objects
	 * 
	 * @param clazz
	 *            the primitive class we want to convert
	 * @param value
	 *            the string value we want to convert into the wrapper class
	 * @return the wrapper class for the given primitive type
	 */
	protected Object toObject(Class clazz, String value) throws IllegalArgumentException {
		IConverter converter = Application.get().getConverterLocator().getConverter(clazz);

		if (converter != null)
			return converter.convertToObject(value, Session.get().getLocale());

		throw new IllegalArgumentException("Could not find a suitable constructor for value "
				+ value + " of class " + clazz);

	}

	protected final boolean hasAny(Roles roles) {
		if (roles.isEmpty()) {
			return true;
		} else {
			return roleCheckingStrategy.hasAnyRole(roles);
		}
	}
}

/**
 * This class contains the informations of a resource's mapped method (i.e. a
 * method annotated with {@link MethodMapping})
 * 
 * @author andrea del bene
 * 
 */
class UrlMappingInfo {
	/**  */
	private final HttpMethod httpMethod;
	/**  */
	private final List<StringValue> segments = new ArrayList<StringValue>();
	/**  */
	private Roles roles = new Roles();
	/**  */
	private final Method method;
	/**  */
	private Class<?>[] notAnnotatedParams;

	/**
	 * Class construnctor.
	 * 
	 * @param urlPath
	 *            the URL used to map a resource's method
	 * @param httpMethod
	 *            the request method that must be used to invoke the mapped
	 *            method (see class {@link HttpMethod}).
	 * @param method
	 *            the resource's method mapped.
	 */
	public UrlMappingInfo(String urlPath, HttpMethod httpMethod, Method method) {
		this.httpMethod = httpMethod;
		this.method = method;

		loadSegments(urlPath);
		loadRoles();
		loadNotAnnotatedParameters();
	}

	private void loadNotAnnotatedParameters() {
		Class<?>[] parameters = method.getParameterTypes();
		Annotation[][] paramsAnnotations = method.getParameterAnnotations();
		
		//no annotated parameters in this method
		if(paramsAnnotations.length == 0){
			notAnnotatedParams = parameters;
			return;
		}
		
		List<Class<?>> notAnnotParams = new ArrayList<Class<?>>();
		
		for (int i = 0; i < parameters.length; i++) {
			Class<?> param = parameters[i];
			
			if(paramsAnnotations[i].length == 0)
				notAnnotParams.add(param);
		}
		
		notAnnotatedParams = notAnnotParams.toArray(parameters);
	}

	private void loadSegments(String urlPath) {
		String[] segArray = urlPath.split("/");

		for (int i = 0; i < segArray.length; i++) {
			String segment = segArray[i];
			StringValue segmentValue;

			if (segment.isEmpty())
				continue;

			if (isParameterSegment(segment))
				segmentValue = new VariableSegment(segment);
			else
				segmentValue = StringValue.valueOf(segment);

			segments.add(segmentValue);
		}
	}

	/**
	 * Utility method to check if a segment contains a parameter (i.e.
	 * '/{parameterName}/').
	 * 
	 * @param segment
	 * @return true if the segment contains a parameter, false otherwise.
	 */
	public static boolean isParameterSegment(String segment) {
		return segment.length() >= 4 && segment.startsWith("{") && segment.endsWith("}");
	}

	private void loadRoles() {
		AuthorizeInvocation authorizeInvocation = method.getAnnotation(AuthorizeInvocation.class);

		if (authorizeInvocation != null) {
			roles = new Roles(authorizeInvocation.value());
		}
	}

	// getters and setters
	public List<StringValue> getSegments() {
		return segments;
	}

	public int getSegmentsCount() {
		return segments.size();
	}

	public HttpMethod getHttpMethod() {
		return httpMethod;
	}

	public Method getMethod() {
		return method;
	}

	public Roles getRoles() {
		return roles;
	}

	public Class<?>[] getNotAnnotatedParams() {
		return notAnnotatedParams;
	}
}

/**
 * {@link StringValue} subtype that contains a mounted segment containing a
 * parameter's value (for example '/{id}/').
 * 
 * @author andrea del bene
 * 
 */
class VariableSegment extends StringValue {
	protected VariableSegment(String text) {
		super(text);
	}
}