package eu.dnetlib.iis.wf.export.actionmanager.module;

import eu.dnetlib.dhp.schema.oaf.ExtraInfo;
import eu.dnetlib.dhp.schema.oaf.Result;
import eu.dnetlib.iis.common.InfoSpaceConstants;
import eu.dnetlib.iis.common.citations.schemas.CitationEntry;
import eu.dnetlib.iis.common.model.conversion.ConfidenceAndTrustLevelConversionUtils;
import eu.dnetlib.iis.common.model.extrainfo.ExtraInfoConstants;
import eu.dnetlib.iis.common.model.extrainfo.citations.BlobCitationEntry;
import eu.dnetlib.iis.common.model.extrainfo.converter.CitationsExtraInfoSerDe;
import eu.dnetlib.iis.export.schemas.Citations;
import eu.dnetlib.iis.wf.export.actionmanager.cfg.StaticConfigurationProvider;
import eu.dnetlib.iis.wf.export.actionmanager.entity.ConfidenceLevelUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link Citations} based action builder module.
 *
 * @author mhorst
 */
public class CitationsActionBuilderModuleFactory extends AbstractActionBuilderFactory<Citations, Result> {

    private static final String EXTRA_INFO_NAME = ExtraInfoConstants.NAME_CITATIONS;
    private static final String EXTRA_INFO_TYPOLOGY = ExtraInfoConstants.TYPOLOGY_CITATIONS;

    // ------------------------ CONSTRUCTORS --------------------------

    public CitationsActionBuilderModuleFactory() {
        super(AlgorithmName.document_referencedDocuments);
    }

    // ------------------------ LOGIC ---------------------------------

    @Override
    public ActionBuilderModule<Citations, Result> instantiate(Configuration config) {
        return new CitationActionBuilderModule(provideTrustLevelThreshold(config));
    }

    // ------------------------ INNER CLASS  --------------------------

    class CitationActionBuilderModule extends AbstractEntityBuilderModule<Citations, Result> {

        private CitationsExtraInfoSerDe citationsExtraInfoSerDe = new CitationsExtraInfoSerDe();
        private CitationEntriesConverter citationEntriesConverter = new CitationEntriesConverter();

        // ------------------------ CONSTRUCTORS --------------------------

        /**
         * @param trustLevelThreshold trust level threshold or null when all records should be exported
         */
        public CitationActionBuilderModule(Float trustLevelThreshold) {
            super(trustLevelThreshold, buildInferenceProvenance());
        }

        // ------------------------ LOGIC --------------------------

        @Override
        protected Class<Result> getResultClass() {
            return Result.class;
        }

        @Override
        protected Result convert(Citations source) {
            if (CollectionUtils.isNotEmpty(source.getCitations())) {
                Result result = new Result();
                result.setId(source.getDocumentId().toString());

                ExtraInfo extraInfo = new ExtraInfo();
                extraInfo.setValue(citationsExtraInfoSerDe.serialize(
                        citationEntriesConverter.convert(source.getCitations(), getTrustLevelThreshold())));
                extraInfo.setName(EXTRA_INFO_NAME);
                extraInfo.setTypology(EXTRA_INFO_TYPOLOGY);
                extraInfo.setProvenance(this.getInferenceProvenance());
                extraInfo.setTrust(StaticConfigurationProvider.ACTION_TRUST_0_9);
                result.setExtraInfo(Collections.singletonList(extraInfo));

                return result;
            }
            return null;
        }

        public void setCitationsExtraInfoSerDe(CitationsExtraInfoSerDe citationsExtraInfoSerDe) {
            this.citationsExtraInfoSerDe = citationsExtraInfoSerDe;
        }

        public void setCitationEntriesConverter(CitationEntriesConverter citationEntriesConverter) {
            this.citationEntriesConverter = citationEntriesConverter;
        }
    }

    /**
     * Allows citation entry list to be converted to blob citation entry set.
     */
    public static class CitationEntriesConverter {
        private Function<Float, Float> trustLevelConverterFn = ConfidenceAndTrustLevelConversionUtils::trustLevelToConfidenceLevel;
        private ConfidenceLevelValidator confidenceLevelValidator = new ConfidenceLevelValidator();
        private CitationEntryNormalizer citationEntryNormalizer = new CitationEntryNormalizer();
        private BlobCitationEntryBuilder blobCitationEntryBuilder = new BlobCitationEntryBuilder();

        public SortedSet<BlobCitationEntry> convert(List<CitationEntry> source, Float trustLevelThreshold) {
            if (source != null) {
                Float confidenceLevelThreshold = trustLevelConverterFn.apply(trustLevelThreshold);
                return source.stream()
                        .map(citationEntry -> confidenceLevelValidator.validate(citationEntry, confidenceLevelThreshold))
                        .map(citationEntry -> citationEntryNormalizer.normalize(citationEntry))
                        .map(citationEntry -> blobCitationEntryBuilder.build(citationEntry))
                        .collect(Collectors.toCollection(TreeSet::new));
            }
            return null;
        }

        /**
         * Allows to check if a citation entry contains the outcome of citation matching.
         */
        public static class CitationEntryMatchChecker {

            public Boolean isMatchingResult(CitationEntry citationEntry) {
                return Objects.nonNull(citationEntry.getConfidenceLevel()) &&
                        Objects.nonNull(citationEntry.getDestinationDocumentId());
            }
        }

        /**
         * Allows to validate confidence level of citation entry using {@link ConfidenceLevelUtils}.
         */
        public static class ConfidenceLevelValidator {
            private CitationEntryMatchChecker citationEntryMatchChecker = new CitationEntryMatchChecker();
            private BiFunction<Float, Float, Boolean> thresholdValidatorFn = ConfidenceLevelUtils::isValidConfidenceLevel;

            public CitationEntry validate(CitationEntry citationEntry, Float confidenceLevelThreshold) {
                if (citationEntryMatchChecker.isMatchingResult(citationEntry) &&
                        !thresholdValidatorFn.apply(citationEntry.getConfidenceLevel(), confidenceLevelThreshold)) {
                    citationEntry.setConfidenceLevel(null);
                    citationEntry.setDestinationDocumentId(null);
                    return citationEntry;
                }
                return citationEntry;
            }
        }

        /**
         * Allows to normalize a citation entry.
         */
        public static class CitationEntryNormalizer {
            /**
             * Normalizes destination document id by removing '50|' prefix from publication identifier
             */
            private Function<CharSequence, CharSequence> destinationDocumentIdNormalizerFn = destinationDocumentId ->
                    StringUtils.split(destinationDocumentId.toString(), InfoSpaceConstants.ROW_PREFIX_SEPARATOR)[1];

            public CitationEntry normalize(CitationEntry citationEntry) {
                if (Objects.nonNull(citationEntry.getDestinationDocumentId())) {
                    citationEntry.setDestinationDocumentId(destinationDocumentIdNormalizerFn.apply(citationEntry.getDestinationDocumentId()));
                }
                return citationEntry;
            }
        }

        /**
         * Allows to build a blob citation entry from citation entry using {@link CitationsActionBuilderModuleUtils}.
         */
        public static class BlobCitationEntryBuilder {
            private Function<CitationEntry, BlobCitationEntry> builderFn = CitationsActionBuilderModuleUtils::build;

            public BlobCitationEntry build(CitationEntry citationEntry) {
                return builderFn.apply(citationEntry);
            }
        }
    }
}