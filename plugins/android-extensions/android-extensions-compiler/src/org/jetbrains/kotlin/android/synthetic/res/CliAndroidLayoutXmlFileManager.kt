/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.android.synthetic.res

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.android.synthetic.AndroidXmlHandler
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

class CliAndroidLayoutXmlFileManager(
        project: Project,
        applicationPackage: String,
        variants: List<AndroidVariant>
) : AndroidLayoutXmlFileManager(project) {
    private companion object {
        val LOG = Logger.getInstance(CliAndroidLayoutXmlFileManager::class.java)
        private fun initSAX(): SAXParser {
            val saxFactory = SAXParserFactory.newInstance()
            saxFactory.isNamespaceAware = true
            return saxFactory.newSAXParser()
        }
    }

    override val androidModule = AndroidModule(applicationPackage, variants)

    private val saxParser: SAXParser = initSAX()

    override fun doExtractResources(files: List<PsiFile>, module: ModuleDescriptor): List<AndroidResource> {
        val resources = arrayListOf<AndroidResource>()

        val handler = AndroidXmlHandler { id, tag ->
            resources += parseAndroidResource(id, tag, null)
        }

        for (file in files) {
            try {
                val inputStream = ByteArrayInputStream(file.virtualFile.contentsToByteArray())
                saxParser.parse(inputStream, handler)
            } catch (e: Throwable) {
                LOG.error(e)
            }
        }

        return resources
    }

}
