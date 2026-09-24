package com.example.dpop.texts

import org.springframework.modulith.ApplicationModule

/**
 * Multilingual texts as a library: [Text], the source wording in the code that leaves the backend
 * only as a reference, and [TextBundle], which serves the reworded languages of a bundle
 * (`texts/<bundle>/texts_<lang>.properties`) by ETag. Knows no journey and no tool, so
 * anything may depend on it - the simulated foreign systems included, which otherwise depend on
 * nothing: a real foreign system would bring its own copy of such a library.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`.
 */
@ApplicationModule(allowedDependencies = [])
internal class ModuleMetadata
