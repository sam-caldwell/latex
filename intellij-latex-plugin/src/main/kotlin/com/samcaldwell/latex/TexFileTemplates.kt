package com.samcaldwell.latex

import com.intellij.ide.fileTemplates.FileTemplateDescriptor
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptor
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptorFactory
import com.intellij.openapi.util.IconLoader

class TexFileTemplates : FileTemplateGroupDescriptorFactory {
  override fun getFileTemplatesDescriptor(): FileTemplateGroupDescriptor {
    val group = FileTemplateGroupDescriptor("LaTeX", IconLoader.getIcon("/icons/tex.svg", javaClass))
    group.addTemplate(FileTemplateDescriptor("LaTeX Article.tex.ft", IconLoader.getIcon("/icons/tex.svg", javaClass)))
    group.addTemplate(FileTemplateDescriptor("LaTeX Article (APA7).tex.ft", IconLoader.getIcon("/icons/tex.svg", javaClass)))
    group.addTemplate(FileTemplateDescriptor("LaTeX Article (MLA).tex.ft", IconLoader.getIcon("/icons/tex.svg", javaClass)))
    group.addTemplate(FileTemplateDescriptor("LaTeX Article (Chicago).tex.ft", IconLoader.getIcon("/icons/tex.svg", javaClass)))
    group.addTemplate(FileTemplateDescriptor("BibTeX Database.bib.ft", IconLoader.getIcon("/icons/bib.svg", javaClass)))
    return group
  }
}
