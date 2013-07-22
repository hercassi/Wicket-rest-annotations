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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

public class MultiParamSegment extends GeneralURLSegment {
	private final List<String> segmentParams = new ArrayList<String>();
	private final List<String> staticSubsegments = new ArrayList<String>();
	
	MultiParamSegment(String text) {
		super(text);
		loadVariables(text);
	}

	private void loadVariables(String text) {
		Matcher matcher = SEGMENT_PARAMETER.matcher(text);
			
		while (matcher.find()) {
			String group = matcher.group();
			String paramName = ParamSegment.trimFirstAndLast(group);
			
			segmentParams.add(paramName);
		}
		
		String[] splittedSegment = text.split(SEGMENT_PARAMETER.toString());
		staticSubsegments.addAll(Arrays.asList(splittedSegment));
	}

	public List<String> getSegmentParams() {
		return segmentParams;
	}

	public List<String> getStaticSubsegments() {
		return staticSubsegments;
	}

}