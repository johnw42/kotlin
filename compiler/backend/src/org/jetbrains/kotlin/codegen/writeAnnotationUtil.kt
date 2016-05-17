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

package org.jetbrains.kotlin.codegen

import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.load.java.JvmAnnotationNames
import org.jetbrains.kotlin.load.java.JvmBytecodeBinaryVersion
import org.jetbrains.kotlin.load.kotlin.JvmMetadataVersion
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.Type
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

fun writeKotlinMetadata(cb: ClassBuilder, kind: KotlinClassHeader.Kind, action: (AnnotationVisitor) -> Unit) {
    val av = cb.newAnnotation(JvmAnnotationNames.METADATA_DESC, true)
    av.visit(JvmAnnotationNames.METADATA_VERSION_FIELD_NAME, JvmMetadataVersion.INSTANCE.toArray())
    av.visit(JvmAnnotationNames.BYTECODE_VERSION_FIELD_NAME, JvmBytecodeBinaryVersion.INSTANCE.toArray())
    av.visit(JvmAnnotationNames.KIND_FIELD_NAME, kind.id)
    action(av)
    av.visitEnd()
}

fun writeSyntheticClassMetadata(cb: ClassBuilder) {
    writeKotlinMetadata(cb, KotlinClassHeader.Kind.SYNTHETIC_CLASS) { av ->
        // Do nothing
    }
}

@JvmOverloads
fun writeExternalMetadata(
        state: GenerationState,
        type: Type,
        cb: ClassBuilder,
        kind: KotlinClassHeader.Kind,
        data: Array<String>,
        strings: Array<String>,
        extraString: String = "",
        extraInt: Int = 0
) {
    fun DataOutputStream.writeStringArray(strings: Array<String>) {
        writeInt(strings.size)
        strings.forEach { writeUTF(it) }
    }

    fun DataOutputStream.writeIntArray(ints: IntArray) {
        writeInt(ints.size)
        ints.forEach { writeInt(it) }
    }

    val av = cb.newAnnotation(JvmAnnotationNames.METADATA_DESC, true)
    av.visit(JvmAnnotationNames.EXTERNAL_FIELD_NAME, true)
    av.visitEnd()

    val baos = ByteArrayOutputStream()
    val output = DataOutputStream(baos)
    output.writeInt(kind.id)
    output.writeIntArray(JvmMetadataVersion.INSTANCE.toArray())
    output.writeIntArray(JvmBytecodeBinaryVersion.INSTANCE.toArray())
    output.writeStringArray(data)
    output.writeStringArray(strings)
    output.writeUTF(extraString)
    output.writeInt(extraInt)

    output.flush()

    state.factory.writeExternalMetadataFile("${type.internalName}.kotlin_metadata", baos.toByteArray())
}
