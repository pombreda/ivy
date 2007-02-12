/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivy.core.deliver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import org.apache.ivy.core.IvyPatternHelper;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorParser;
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorUpdater;
import org.apache.ivy.util.Message;
import org.xml.sax.SAXException;

public class DeliverEngine {
	private IvySettings _settings;
	
    public DeliverEngine(IvySettings settings) {
		_settings = settings;
	}

    /**
     * Delivers a resolved ivy file based upon last resolve call status.
     * 
     * If resolve report file cannot be found in cache, then it throws 
     * an IllegalStateException (maybe resolve has not been called before ?).
     *
     * @param mrid the module revision id of the module to deliver
     * @param revision the revision to which the module should be delivered
     * @param destIvyPattern the pattern to which the delivered ivy file should be written
     * @param options the options with which deliver should be done 
     */
    public void deliver(ModuleRevisionId mrid, String revision, 
    		String destIvyPattern, DeliverOptions options) throws IOException, ParseException {
        Message.info(":: delivering :: "+mrid+" :: "+revision+" :: "+options.getStatus()+" :: "+options.getPubdate());
        Message.verbose("\toptions = "+options);
        long start = System.currentTimeMillis();
        destIvyPattern = _settings.substitute(destIvyPattern);
        
        // 1) find the resolved module descriptor in cache
        File ivyFile = options.getCache().getResolvedIvyFileInCache(mrid);
        if (!ivyFile.exists()) {
            throw new IllegalStateException("ivy file not found in cache for "+mrid+": please resolve dependencies before publishing ("+ivyFile+")");
        }
        ModuleDescriptor md = null;
        URL ivyFileURL = null;
        try {
            ivyFileURL = ivyFile.toURL();
            md = XmlModuleDescriptorParser.getInstance().parseDescriptor(_settings, ivyFileURL, options.isValidate());
            md.setResolvedModuleRevisionId(ModuleRevisionId.newInstance(mrid, revision));
            md.setResolvedPublicationDate(options.getPubdate());
        } catch (MalformedURLException e) {
            throw new RuntimeException("malformed url obtained for file "+ivyFile , e);
        } catch (ParseException e) {
            throw new RuntimeException("bad ivy file in cache for "+mrid+": please clean and resolve again" , e);
        }
        
        // 2) parse resolvedRevisions From properties file
        Map resolvedRevisions = new HashMap(); // Map (ModuleId -> String revision)
        Map dependenciesStatus = new HashMap(); // Map (ModuleId -> String status)
        File ivyProperties = options.getCache().getResolvedIvyPropertiesInCache(mrid);
        if (!ivyProperties.exists()) {
            throw new IllegalStateException("ivy properties not found in cache for "+mrid+": please resolve dependencies before publishing ("+ivyFile+")");
        }
        Properties props = new Properties();
        props.load(new FileInputStream(ivyProperties));
        
        for (Iterator iter = props.keySet().iterator(); iter.hasNext();) {
            String depMridStr = (String)iter.next();
            String[] parts = props.getProperty(depMridStr).split(" ");
            ModuleRevisionId decodedMrid = ModuleRevisionId.decode(depMridStr);
            if (options.isResolveDynamicRevisions()) {
                resolvedRevisions.put(decodedMrid, parts[0]);
            }
            dependenciesStatus.put(decodedMrid, parts[1]);
        }
        
        // 3) use pdrResolver to resolve dependencies info
        Map resolvedDependencies = new HashMap(); // Map (ModuleRevisionId -> String revision)
        DependencyDescriptor[] dependencies = md.getDependencies();
        for (int i = 0; i < dependencies.length; i++) {
            String rev = (String)resolvedRevisions.get(dependencies[i].getDependencyRevisionId());
            if (rev == null) {
                rev = dependencies[i].getDependencyRevisionId().getRevision();
            }
            String depStatus = (String)dependenciesStatus.get(dependencies[i].getDependencyRevisionId());
            resolvedDependencies.put(dependencies[i].getDependencyRevisionId(), 
                    options.getPdrResolver().resolve(md, options.getStatus(), 
                            ModuleRevisionId.newInstance(dependencies[i].getDependencyRevisionId(), rev), 
                            depStatus));
        }
        
        // 4) copy the source resolved ivy to the destination specified, 
        //    updating status, revision and dependency revisions obtained by
        //    PublishingDependencyRevisionResolver
        String publishedIvy = IvyPatternHelper.substitute(destIvyPattern, md.getResolvedModuleRevisionId());
        Message.info("\tdelivering ivy file to "+publishedIvy);
        try {
            XmlModuleDescriptorUpdater.update(_settings, ivyFileURL, 
                    new File(publishedIvy),
                    resolvedDependencies, options.getStatus(), revision, options.getPubdate(), null, true);
        } catch (SAXException ex) {
            throw new RuntimeException("bad ivy file in cache for "+mrid+": please clean and resolve again" , ex);
        }
        
        Message.verbose("\tdeliver done ("+(System.currentTimeMillis()-start)+"ms)");
    }
}