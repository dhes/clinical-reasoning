package org.opencds.cqf.fhir.cr.cpg.r4;

import static org.opencds.cqf.fhir.utility.r4.Parameters.parameters;
import static org.opencds.cqf.fhir.utility.r4.Parameters.part;

import ca.uhn.fhir.repository.IRepository;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.opencds.cqf.fhir.cql.Engines;
import org.opencds.cqf.fhir.cql.EvaluationSettings;
import org.opencds.cqf.fhir.cql.LibraryEngine;
import org.opencds.cqf.fhir.cr.cpg.CqlExecutionProcessor;
import org.opencds.cqf.fhir.utility.repository.Repositories;

public class R4CqlExecutionService {

    protected IRepository repository;
    protected EvaluationSettings evaluationSettings;

    public R4CqlExecutionService(IRepository repository, EvaluationSettings evaluationSettings) {
        this.repository = repository;
        this.evaluationSettings = evaluationSettings;
    }

    // should use adapters to make this version agnostic
    public Parameters evaluate(
            // RequestDetails requestDetails,
            String subject,
            String expression,
            Parameters parameters,
            List<Parameters> library,
            BooleanType useServerData,
            Bundle data,
            List<Parameters> prefetchData,
            Endpoint dataEndpoint,
            Endpoint contentEndpoint,
            Endpoint terminologyEndpoint,
            String content) {


        var baseCqlExecutionProcessor = new CqlExecutionProcessor();

        if (prefetchData != null) {
            return parameters(part("invalid parameters", (OperationOutcome)
                    baseCqlExecutionProcessor.createIssue("warning", "prefetchData is not yet supported", repository)));
        }

        if (expression == null && content == null) {
            return parameters(part("invalid parameters", (OperationOutcome) baseCqlExecutionProcessor.createIssue(
                    "error",
                    "The $cql operation requires the expression parameter and/or content parameter to exist",
                    repository)));
        }

        try {
            if (contentEndpoint != null) {
                repository = Repositories.proxy(
                        repository, useServerData.booleanValue(), dataEndpoint, contentEndpoint, terminologyEndpoint);
            }
            var libraryEngine = new LibraryEngine(repository, this.evaluationSettings);

            var libraries = baseCqlExecutionProcessor.resolveIncludedLibraries(library);

            if (StringUtils.isBlank(content)) {

                return (Parameters) libraryEngine.evaluateExpression(
                        expression,
                        parameters == null ? new Parameters() : parameters,
                        null,
                        subject,
                        libraries,
                        data,
                        null,
                        null);
            }

            // Pre-compile inline CQL content and add to cache before creating engine
            org.hl7.elm.r1.VersionedIdentifier libraryIdentifier = null;
            if (!StringUtils.isBlank(content)) {
                try {
                    // Create a temporary library manager to resolve the library identifier
                    var tempModelManager = new org.cqframework.cql.cql2elm.ModelManager();
                    var tempLibraryManager = new org.cqframework.cql.cql2elm.LibraryManager(
                            tempModelManager, 
                            evaluationSettings.getCqlOptions().getCqlCompilerOptions());
                    libraryIdentifier = baseCqlExecutionProcessor.resolveLibraryIdentifier(content, null, tempLibraryManager);
                    
                    if (libraryIdentifier != null) {
                        // Compile the CQL content
                        var translator = org.cqframework.cql.cql2elm.CqlTranslator.fromText(content, tempLibraryManager);
                        if (translator.getTranslatedLibrary() != null) {
                            // Add the compiled library to the evaluation settings cache BEFORE creating the engine
                            evaluationSettings.getLibraryCache().put(libraryIdentifier, translator.getTranslatedLibrary());
                            
                        }
                    }
                } catch (Exception ex) {
                    return parameters(part("evaluation error", (OperationOutcome)
                            baseCqlExecutionProcessor.createIssue("error", "Failed to compile inline CQL content: " + ex.getMessage(), repository)));
                }
            }
            
            // Now create the engine - it will use the updated cache
            var engine = Engines.forRepository(repository, evaluationSettings, null);

            // If we don't have a library identifier from content, we need to handle this case
            if (libraryIdentifier == null) {
                return parameters(part("evaluation error", (OperationOutcome)
                        baseCqlExecutionProcessor.createIssue("error", "Could not resolve library identifier from content", repository)));
            }

            // Use the engine we created with the cached library, not libraryEngine.evaluate() 
            // which would create a new engine without our cache
            var cqlFhirParametersConverter = Engines.getCqlFhirParametersConverter(repository.fhirContext());
            var evaluationParameters = cqlFhirParametersConverter.toCqlParameters(parameters);
            
            var contextParameter = subject != null ? 
                org.apache.commons.lang3.tuple.Pair.<String, Object>of("Patient", subject.startsWith("Patient/") ? subject.replace("Patient/", "") : subject) : 
                null;
            
            var expressions = expression == null ? null : Collections.singleton(expression);
            
            var result = engine.evaluate(
                    libraryIdentifier,
                    expressions,
                    contextParameter,
                    evaluationParameters,
                    null,
                    null);
            
            return (Parameters) cqlFhirParametersConverter.toFhirParameters(result);

        } catch (Exception e) {
            return parameters(part("evaluation error", (OperationOutcome)
                    baseCqlExecutionProcessor.createIssue("error", e.getMessage(), repository)));
        }
    }
}
