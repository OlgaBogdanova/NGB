/*
 * MIT License
 *
 * Copyright (c) 2016 EPAM Systems
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.epam.catgenome.manager.vcf;

import static com.epam.catgenome.component.MessageHelper.getMessage;
import static com.epam.catgenome.constant.MessagesConstants.ERROR_REGISTER_FILE;
import static com.epam.catgenome.constant.MessagesConstants.ERROR_VCF_ID_INVALID;
import static com.epam.catgenome.constant.MessagesConstants.ERROR_VCF_INDEX;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.codehaus.jettison.json.JSONException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.epam.catgenome.constant.MessagesConstants;
import com.epam.catgenome.controller.vo.ga4gh.CallSet;
import com.epam.catgenome.controller.vo.ga4gh.CallSetSearch;
import com.epam.catgenome.controller.vo.registration.FeatureIndexedFileRegistrationRequest;
import com.epam.catgenome.controller.vo.registration.IndexedFileRegistrationRequest;
import com.epam.catgenome.entity.BaseEntity;
import com.epam.catgenome.entity.BiologicalDataItem;
import com.epam.catgenome.entity.BiologicalDataItemFormat;
import com.epam.catgenome.entity.BiologicalDataItemResourceType;
import com.epam.catgenome.entity.gene.GeneFile;
import com.epam.catgenome.entity.index.VcfIndexEntry;
import com.epam.catgenome.entity.reference.Chromosome;
import com.epam.catgenome.entity.reference.Reference;
import com.epam.catgenome.entity.track.Track;
import com.epam.catgenome.entity.track.TrackType;
import com.epam.catgenome.entity.vcf.InfoItem;
import com.epam.catgenome.entity.vcf.Variation;
import com.epam.catgenome.entity.vcf.VariationQuery;
import com.epam.catgenome.entity.vcf.VcfFile;
import com.epam.catgenome.entity.vcf.VcfFilterInfo;
import com.epam.catgenome.entity.vcf.VcfSample;
import com.epam.catgenome.exception.ExternalDbUnavailableException;
import com.epam.catgenome.exception.FeatureFileReadingException;
import com.epam.catgenome.exception.FeatureIndexException;
import com.epam.catgenome.exception.GeneReadingException;
import com.epam.catgenome.exception.RegistrationException;
import com.epam.catgenome.exception.VcfReadingException;
import com.epam.catgenome.manager.BiologicalDataItemManager;
import com.epam.catgenome.manager.DownloadFileManager;
import com.epam.catgenome.manager.FeatureIndexManager;
import com.epam.catgenome.manager.FileManager;
import com.epam.catgenome.manager.TrackHelper;
import com.epam.catgenome.manager.externaldb.HttpDataManager;
import com.epam.catgenome.manager.reference.ReferenceGenomeManager;
import com.epam.catgenome.manager.vcf.reader.AbstractVcfReader;
import com.epam.catgenome.manager.vcf.reader.VcfFileReader;
import com.epam.catgenome.manager.vcf.reader.VcfGa4ghReader;
import com.epam.catgenome.manager.vcf.reader.VcfReader;
import com.epam.catgenome.util.AuthUtils;
import com.epam.catgenome.util.IOHelper;
import com.epam.catgenome.util.Utils;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.tribble.AbstractFeatureReader;
import htsjdk.tribble.FeatureReader;
import htsjdk.tribble.TribbleException;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.vcf.VCFSimpleHeaderLine;

/**
 * {@code VcfManager} represents a service class designed to encapsulate all business
 * logic operations required to manage {@code VcfFile} and corresponded tracks, e.g. to process
 * variants uploads, position-based and/or zoom queries etc.
 */
@Service
public class VcfManager {

    @Autowired
    private FileManager fileManager;

    @Autowired
    private VcfFileManager vcfFileManager;

    @Autowired
    private ReferenceGenomeManager referenceGenomeManager;

    @Autowired
    private BiologicalDataItemManager biologicalDataItemManager;

    @Autowired
    private TrackHelper trackHelper;

    @Autowired
    private HttpDataManager httpDataManager;

    @Autowired
    private DownloadFileManager downloadFileManager;

    @Autowired
    private FeatureIndexManager featureIndexManager;

    public static final double HTSJDK_WRONG_QUALITY = -10.0;

    @Value("#{catgenome['vcf.filter.whitelist']}")
    private String[] whiteArray;
    private List<String> whiteList;

    private static final Logger LOGGER = LoggerFactory.getLogger(VcfManager.class);

    /**
     * Registers a VCF file in the system to make it available to browse. Creates Tribble/Tabix index if absent
     * and a feature index to allow fast search for variations
     *
     * @param request a request for file registration, containing path to file, reference ID and optional parameters:
     *                path to index file and vcf file name to save in the system
     * @return a {@code VcfFile} that was registered
     */
    public VcfFile registerVcfFile(FeatureIndexedFileRegistrationRequest request) {
        final String requestPath = request.getPath();
        Assert.isTrue(StringUtils.isNotBlank(requestPath), getMessage(
                MessagesConstants.ERROR_NULL_PARAM, "path"));
        Assert.notNull(request.getReferenceId(), getMessage(MessagesConstants.ERROR_NULL_PARAM, "referenceId"));

        VcfFile vcfFile;
        Reference reference = referenceGenomeManager.loadReferenceGenome(request.getReferenceId());
        Map<String, Chromosome> chromosomeMap = reference.getChromosomes().stream().collect(
                Collectors.toMap(BaseEntity::getName, chromosome -> chromosome));
        if (request.getType() == null) {
            request.setType(BiologicalDataItemResourceType.FILE);
        }
        switch (request.getType()) {
            case GA4GH:
                vcfFile = getVcfFileFromGA4GH(request, requestPath);
                break;
            case FILE:
                vcfFile = createVcfFromFile(request, chromosomeMap, reference, request.isDoIndex());
                break;
            case DOWNLOAD:
                vcfFile = downloadVcfFile(request, requestPath, chromosomeMap, reference, request.isDoIndex());
                break;
            case URL:
                vcfFile = createVcfFromUrl(request, chromosomeMap, reference);
                break;
            default:
                throw new IllegalArgumentException(getMessage(MessagesConstants.ERROR_INVALID_PARAM));
        }
        biologicalDataItemManager.createBiologicalDataItem(vcfFile.getIndex());
        vcfFileManager.createVcfFile(vcfFile);
        return vcfFile;
    }

    /**
     * Delete vcf file metadata from database and feature file directory.
     *
     * @param vcfFileId id of file to remove
     * @return deleted file
     * @throws IOException if error occurred during deleting feature file directory
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public VcfFile unregisterVcfFile(final Long vcfFileId) throws IOException {
        Assert.notNull(vcfFileId, MessagesConstants.ERROR_INVALID_PARAM);
        Assert.isTrue(vcfFileId > 0, MessagesConstants.ERROR_INVALID_PARAM);
        VcfFile vcfFile = vcfFileManager.loadVcfFile(vcfFileId);
        Assert.notNull(vcfFile, MessagesConstants.ERROR_NO_SUCH_FILE);
        vcfFileManager.deleteVcfFile(vcfFile);
        if (vcfFile.getType() == BiologicalDataItemResourceType.GA4GH) {
            return vcfFile;
        }
        fileManager.deleteFeatureFileDirectory(vcfFile);
        return vcfFile;
    }

    /**
     * Loads variations for a specified track, for a specified sample
     *
     * @param track    a {@code Track} to load variations for
     * @param sampleId specifies sample to load variations for
     * @param loadInfo specifies if extended info should be loaded
     * @param collapse flag determines if variations should be collapsed on small scale
     * @return a {@code Track} with variations
     */
    public Track<Variation> loadVariations(final Track<Variation> track, final Long sampleId, boolean loadInfo,
                                           final boolean collapse)
            throws VcfReadingException {
        Chromosome chromosome = trackHelper.validateTrack(track);

        final VcfFile vcfFile = vcfFileManager.loadVcfFile(track.getId());
        Assert.notNull(vcfFile, getMessage(ERROR_VCF_ID_INVALID, track.getId()));
        final Integer sampleIndex = getSampleIndex(sampleId, vcfFile);
        Assert.notNull(vcfFile.getIndex(), getMessage(ERROR_VCF_INDEX, track.getId()));
        if (track.getType() == null) {
            track.setType(TrackType.VCF);
        }

        AbstractVcfReader.createVcfReader(vcfFile.getType(), httpDataManager, fileManager,
                referenceGenomeManager).readVariations(vcfFile, track, chromosome, sampleIndex, loadInfo, collapse);

        return track;
    }

    /**
     * Loads variations for a specified track, for a specified sample
     *
     * @param track    a {@code Track} to load variations for
     * @param sampleIndex specifies sample to load variations for
     * @param fileUrl URL of VCF file resource
     * @param indexUrl URL of VCF index resource
     * @param loadInfo specifies if extended info should be loaded
     * @param collapse flag determines if variations should be collapsed on small scale
     * @return a {@code Track} with variations
     */
    public Track<Variation> loadVariations(final Track<Variation> track, String fileUrl, String indexUrl,
                                           final Integer sampleIndex, final boolean loadInfo, final boolean collapse)
        throws VcfReadingException {
        Chromosome chromosome = trackHelper.validateUrlTrack(track, fileUrl, indexUrl);

        VcfFile notRegisteredFile = new VcfFile();
        notRegisteredFile.setPath(fileUrl);
        notRegisteredFile.setCompressed(false);
        notRegisteredFile.setType(BiologicalDataItemResourceType.URL);
        notRegisteredFile.setReferenceId(chromosome.getReferenceId());

        BiologicalDataItem index = new BiologicalDataItem();
        index.setPath(indexUrl);
        notRegisteredFile.setIndex(index);

        if (track.getType() == null) {
            track.setType(TrackType.VCF);
        }

        AbstractVcfReader.createVcfReader(BiologicalDataItemResourceType.URL, httpDataManager, fileManager,
                                          referenceGenomeManager).readVariations(notRegisteredFile, track, chromosome,
                                                                                 sampleIndex, loadInfo, collapse);
        return track;
    }

    /**
     * Loads a single variation with extended info
     *
     * @param query {@code VariationQuery}, defining variation to load
     * @return desired {@code Variation} from VCF file
     */
    public Variation loadVariation(final VariationQuery query) throws FeatureFileReadingException {
        // converts query to a simple track corresponded to a single nucleotide position, where a particular
        // variation should be presented

        final Track<Variation> track = new Track<>();
        track.setScaleFactor(1D);
        track.setId(query.getId());
        track.setEndIndex(query.getPosition());
        track.setStartIndex(query.getPosition());
        track.setChromosome(referenceGenomeManager.loadChromosome(query.getChromosomeId()));

        // tries to load variation
        loadVariations(track, query.getSampleId(), true, false);
        Assert.notEmpty(track.getBlocks(), getMessage(MessagesConstants.ERROR_NO_SUCH_VARIATION, query.getPosition()));
        Variation variation = track.getBlocks().get(0);

        VcfFile vcfFile = vcfFileManager.loadVcfFile(query.getId());
        Reference reference = referenceGenomeManager.loadReferenceGenome(vcfFile.getReferenceId());
        if (reference.getGeneFile() != null) {
            Set<String> geneIds = featureIndexManager.fetchGeneIds(variation.getStartIndex(),
                                                                   variation.getEndIndex(),
                                                                   Collections.singletonList(reference.getGeneFile()),
                                                                   track.getChromosome());
            variation.setGeneNames(geneIds);
        }

        return variation;
    }

    /**
     * Loads a single variation with extended info
     *
     * @param query {@code VariationQuery}, defining variation to load
     * @param fileUrl URL of VCF file resource
     * @param indexUrl URL of VCF index resource
     * @return desired {@code Variation} from VCF file
     */
    public Variation loadVariation(final VariationQuery query, String fileUrl, String indexUrl)
        throws FeatureFileReadingException {
        // converts query to a simple track corresponded to a single nucleotide position, where a particular
        // variation should be presented

        final Track<Variation> track = new Track<>();
        track.setScaleFactor(1D);
        track.setEndIndex(query.getPosition());
        track.setStartIndex(query.getPosition());
        track.setChromosome(new Chromosome(query.getChromosomeId()));

        // tries to load variation
        loadVariations(track, fileUrl, indexUrl, query.getSampleId() != null ? query.getSampleId().intValue() : null,
                       true, false);
        Assert.notEmpty(track.getBlocks(), getMessage(MessagesConstants.ERROR_NO_SUCH_VARIATION, query.getPosition()));
        Variation variation = track.getBlocks().get(0);

        Reference reference = referenceGenomeManager.loadReferenceGenome(track.getChromosome().getReferenceId());
        if (reference.getGeneFile() != null) {
            Set<String> geneIds = featureIndexManager.fetchGeneIds(variation.getStartIndex(),
                                                                   variation.getEndIndex(),
                                                                   Collections.singletonList(reference.getGeneFile()),
                                                                   track.getChromosome());
            variation.setGeneNames(geneIds);
        }

        return variation;
    }

    /**
     * Returns next/previous variation of the specified chromosome in specified VCF file
     *
     * @param fromPosition {@code int} the position from which look for next/previous variation
     * @param vcfFileId    {@code int} ID of the VCF file
     * @param chromosomeId {@code int} ID of the chromosome
     * @param sampleId     {@code Integer} ID of the desired sample to search, can be null
     * @param forward      {@code boolean} flag that determines direction to look for feature
     * @return {@code Gene} next or previous feature
     */
    public Variation getNextOrPreviousVariation(final int fromPosition, final long vcfFileId, final Long sampleId,
                                                final long chromosomeId, final boolean forward)
            throws VcfReadingException {
        final Chromosome chromosome = referenceGenomeManager.loadChromosome(chromosomeId);
        Assert.notNull(chromosome, getMessage(MessagesConstants.ERROR_CHROMOSOME_ID_NOT_FOUND));
        int end = forward ? chromosome.getSize() : 0;
        if ((forward && fromPosition + 1 >= end) || (!forward && fromPosition - 1 <= end)) { // no next features
            return null;
        }
        final VcfFile vcfFile = vcfFileManager.loadVcfFile(vcfFileId);
        Assert.notNull(vcfFile, getMessage(ERROR_VCF_ID_INVALID, vcfFileId));
        Assert.notNull(vcfFile.getIndex(), getMessage(ERROR_VCF_INDEX, vcfFileId));

        VcfReader vcfReader = AbstractVcfReader.createVcfReader(vcfFile.getType(), httpDataManager, fileManager,
                referenceGenomeManager);
        Integer sampleIndex = getSampleIndex(sampleId, vcfFile);
        return vcfReader.getNextOrPreviousVariation(fromPosition, vcfFile, sampleIndex, chromosome, forward);
    }

    /**
     * Loads VCF FILTER and INFO data for a {@code Collection} of VCF files
     * @param vcfFileIds {@code Collection} specifies VCF giles of interest
     * @return  VCF FILTER and INFO data
     * @throws IOException if an error with file system occurred
     */
    public VcfFilterInfo getFiltersInfo(Collection<Long> vcfFileIds) throws IOException {
        VcfFilterInfo filterInfo = new VcfFilterInfo();
        Set<InfoItem> infoItems = new HashSet<>();
        Set<String> availableFilters = new HashSet<>();

        for (Long fileId : vcfFileIds) {
            VcfFile vcfFile = vcfFileManager.loadVcfFile(fileId);
            Assert.notNull(vcfFile, getMessage(ERROR_VCF_ID_INVALID, fileId));

            try (FeatureReader<VariantContext> reader = AbstractFeatureReader.getFeatureReader(vcfFile.getPath(),
                    new VCFCodec(), false)) {
                VCFHeader header = (VCFHeader) reader.getHeader();
                Collection<VCFInfoHeaderLine> headerLines = header.getInfoHeaderLines();
                infoItems.addAll(headerLines.stream()
                        .filter(l -> !"ANN".equalsIgnoreCase(l.getID()))    // Exclude ANN from fields,
                        .map(InfoItem::new).collect(Collectors.toList())); // we don't need it in the index
                availableFilters.addAll(header.getFilterLines().stream().map(VCFSimpleHeaderLine::getID)
                        .collect(Collectors.toList()));
            }

        }

        List<String> filtersWhiteList = getFilterWhiteList();
        if (!filtersWhiteList.isEmpty()) {
            infoItems = scourFilterList(infoItems, filtersWhiteList);
        }

        filterInfo.setInfoItems(infoItems);
        filterInfo.setAvailableFilters(availableFilters);

        return filterInfo;
    }

    /**
     * Creates a feature index for {@link VcfFile}. If an index already exists, it will be deleted and created
     * from scratch
     * @param vcfFileId an ID of VCF file to reindex.
     * @throws FeatureIndexException if an error occurred while writing index
     */
    public VcfFile reindexVcfFile(long vcfFileId) throws FeatureIndexException {
        VcfFile vcfFile = vcfFileManager.loadVcfFile(vcfFileId);
        Reference reference = referenceGenomeManager.loadReferenceGenome(vcfFile.getReferenceId());
        Map<String, Chromosome> chromosomeMap = reference.getChromosomes().stream().collect(
            Collectors.toMap(BaseEntity::getName, chromosome -> chromosome));
        List<GeneFile> geneFiles = reference.getGeneFile() != null ?
                                   Collections.singletonList(reference.getGeneFile()) :
                                   Collections.emptyList();

        try {
            fileManager.deleteFileFeatureIndex(vcfFile);

            try (FeatureReader<VariantContext> reader = AbstractFeatureReader.getFeatureReader(vcfFile.getPath(),
                                                                                               new VCFCodec(), false)) {
                VcfFilterInfo info = getFiltersInfo(reader);
                featureIndexManager.makeIndexForVcfReader(vcfFile, reader, geneFiles, chromosomeMap, info);
            }
        } catch (IOException e) {
            throw new FeatureIndexException(vcfFile, e);
        }

        return vcfFile;
    }

    private VcfFilterInfo getFiltersInfo(FeatureReader<VariantContext> reader) throws IOException {
        VcfFilterInfo filterInfo = new VcfFilterInfo();
        Set<InfoItem> infoItems = new HashSet<>();

        VCFHeader header = (VCFHeader) reader.getHeader();
        Collection<VCFInfoHeaderLine> headerLines = header.getInfoHeaderLines();
        infoItems.addAll(headerLines.stream()
                             .filter(l -> !"ANN".equalsIgnoreCase(l.getID()))    // Exclude ANN from fields,
                             .map(InfoItem::new).collect(Collectors.toList())); // we don't need it in the index
        filterInfo.setAvailableFilters(header.getFilterLines().stream().map(VCFSimpleHeaderLine::getID)
                                    .collect(Collectors.toSet()));

        List<String> filtersWhiteList = getFilterWhiteList();
        if (!filtersWhiteList.isEmpty()) {
            infoItems = scourFilterList(infoItems, filtersWhiteList);
        }

        filterInfo.setInfoItems(infoItems);

        return filterInfo;
    }

    /**
     * Returns a white list of VCF Info fields, that are available for filtering
     *
     * @return a {@code List} of field names
     */
    private List<String> getFilterWhiteList() {
        if (whiteList == null) {
            if (whiteArray == null) {
                return Collections.emptyList();
            } else {
                whiteList = Arrays.asList(whiteArray);
            }
        }
        return whiteList;
    }

    private VcfFile createVcfFromFile(final IndexedFileRegistrationRequest request,
            final Map<String, Chromosome> chromosomeMap, Reference reference, boolean doIndex) {
        final VcfFile vcfFile;
        try (FeatureReader<VariantContext> reader = AbstractFeatureReader
                .getFeatureReader(request.getPath(), request.getIndexPath(), new VCFCodec(),
                        request.getIndexPath() != null)) {
            vcfFile = createVcfFile(request, reader);
            fileManager.makeVcfDir(vcfFile.getId(), AuthUtils.getCurrentUserId());
            if (StringUtils.isBlank(request.getIndexPath())) {
                fileManager.makeVcfIndex(vcfFile, AuthUtils.getCurrentUserId());
            }

            // In order to fix bugs with zipped VCF
            Map<String, Pair<Integer, Integer>> metaMap =
                    readMetaMap(chromosomeMap, vcfFile, reader, reference, doIndex);
            fileManager.makeIndexMetadata(vcfFile, metaMap);
        }  catch (IOException | GeneReadingException e) {
            throw new RegistrationException(getMessage(ERROR_REGISTER_FILE, request.getName()), e);
        }

        return vcfFile;
    }

    private VcfFile createVcfFromUrl(final IndexedFileRegistrationRequest request,
                                      final Map<String, Chromosome> chromosomeMap, Reference reference) {
        final VcfFile vcfFile;
        try (FeatureReader<VariantContext> reader = AbstractFeatureReader.getFeatureReader(request.getPath(),
                                           request.getIndexPath(), new VCFCodec(), true)) {
            vcfFile = createVcfFile(request, reader);
            boolean hasVariations = false;
            for (Map.Entry<String, Chromosome> chrEntry : chromosomeMap.entrySet()) {
                CloseableIterator<VariantContext> iterator = Utils.query(reader, chrEntry.getKey(), 1,
                                                                         chrEntry.getValue().getSize());
                if (iterator.hasNext()) {
                    hasVariations = true;
                    break;
                }
            }

            Assert.isTrue(hasVariations, getMessage(MessagesConstants.ERROR_FILE_CORRUPTED_OR_EMPTY, request.getPath(),
                                                    reference.getName()));
        }  catch (IOException e) {
            throw new RegistrationException(getMessage(ERROR_REGISTER_FILE, request.getName()), e);
        }

        return vcfFile;
    }

    @NotNull
    private Map<String, Pair<Integer, Integer>> readMetaMap(Map<String, Chromosome> chromosomeMap,
            VcfFile vcfFile, FeatureReader<VariantContext> reader, Reference reference, boolean doIndex)
        throws IOException, GeneReadingException {
        Map<String, Pair<Integer, Integer>> metaMap = new HashMap<>();
        CloseableIterator<VariantContext> iterator = reader.iterator();
        int startPosition = 1;
        int endPosition = 1;
        String currentKey = null;
        VariantContext variantContext = null;
        VariantContext lastFeature = null;

        VcfFilterInfo info = getFiltersInfo(reader);
        VcfFileReader vcfFileReader = new VcfFileReader(fileManager, referenceGenomeManager);
        VCFHeader vcfHeader = (VCFHeader) reader.getHeader();
        List<VcfIndexEntry> allEntries = new ArrayList<>();
        List<GeneFile> geneFiles  = reference.getGeneFile() != null ?
                                    Collections.singletonList(reference.getGeneFile()) : Collections.emptyList();

        while (iterator.hasNext()) {
            variantContext = iterator.next();

            if (!variantContext.getContig().equals(currentKey)) {
                if (checkMetaMapKey(chromosomeMap, currentKey)) {
                    metaMap.put(currentKey, new ImmutablePair<>(startPosition, endPosition));

                    writeEntriesForChromosome(allEntries, geneFiles,
                                              Utils.getFromChromosomeMap(chromosomeMap, currentKey), vcfFile, vcfHeader,
                                              vcfFileReader, doIndex);
                }

                startPosition = variantContext.getStart();
                currentKey = variantContext.getContig();
            }

            checkSorted(vcfFile, variantContext, lastFeature);

            indexVariation(allEntries, variantContext, chromosomeMap, info, vcfHeader, vcfFileReader, doIndex);

            lastFeature = variantContext;
            // Put the last one in metaMap
            endPosition = variantContext.getStart();

            if (checkMetaMapKey(chromosomeMap, currentKey)) {
                metaMap.put(currentKey, new ImmutablePair<>(startPosition, endPosition));
            }
        }

        // Put the last one
        if (variantContext != null && checkMetaMapKey(chromosomeMap, currentKey)) {
            writeEntriesForChromosome(allEntries, geneFiles, Utils.getFromChromosomeMap(chromosomeMap, currentKey),
                                      vcfFile, vcfHeader, vcfFileReader, doIndex);
        }

        return metaMap;
    }

    private void indexVariation(List<VcfIndexEntry> allEntries, VariantContext variantContext,
                                Map<String, Chromosome> chromosomeMap,
                                VcfFilterInfo info, VCFHeader vcfHeader,
                                VcfFileReader vcfFileReader, boolean doIndex) {
        if (doIndex) {
            featureIndexManager.addVariationToIndex(allEntries, variantContext, chromosomeMap, info, vcfHeader,
                                                    vcfFileReader);
        }
    }

    private void writeEntriesForChromosome(List<VcfIndexEntry> allEntries, List<GeneFile> geneFiles,
                                           Chromosome currentChromosome, VcfFile vcfFile,
                                           VCFHeader vcfHeader, VcfFileReader vcfFileReader, boolean doIndex)
        throws GeneReadingException, IOException {
        if (doIndex) {
            List<VcfIndexEntry> processedEntries = featureIndexManager.postProcessIndexEntries(allEntries, geneFiles,
                                                                                           currentChromosome,
                                                                                           vcfHeader, vcfFileReader);
            featureIndexManager.writeLuceneIndexForFile(vcfFile, processedEntries);
            LOGGER.info(getMessage(MessagesConstants.INFO_FEATURE_INDEX_CHROMOSOME_WROTE, currentChromosome.getName()));
            allEntries.clear();
        }
    }

    private boolean checkMetaMapKey(Map<String, Chromosome> chromosomeMap, String currentKey) {
        return currentKey != null && Utils.chromosomeMapContains(chromosomeMap, currentKey);
    }

    private VcfFile createVcfGA4GH(final IndexedFileRegistrationRequest request) {

        VcfFile vcfFile = new VcfFile();
        vcfFile.setId(vcfFileManager.createVcfFileId());
        vcfFile.setCompressed(true);
        vcfFile.setPath(request.getPath());
        vcfFile.setName(request.getName() != null ? request.getName() : request.getPath());
        vcfFile.setType(BiologicalDataItemResourceType.GA4GH); // For now we're working only with files
        vcfFile.setCreatedDate(new Date());
        vcfFile.setCreatedBy(AuthUtils.getCurrentUserId());
        vcfFile.setReferenceId(request.getReferenceId());
        VcfGa4ghReader reader = new VcfGa4ghReader(httpDataManager, referenceGenomeManager);
        CallSetSearch callSetSearch;
        try {
            callSetSearch = reader.callSetSearch(vcfFile.getPath());
        } catch (JSONException | InterruptedException | ExternalDbUnavailableException | IOException e) {
            throw new RegistrationException(vcfFile.getName(), e);
        }
        Map<String, Integer> sampleMap = getSampleNameToOffset(callSetSearch.getCallSets());
        if (sampleMap != null && !sampleMap.isEmpty()) {
            List<VcfSample> samples = sampleMap.entrySet().stream().map(e -> new VcfSample(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            vcfFile.setSamples(samples);
        }
        if (StringUtils.isNotBlank(request.getIndexPath())) {
            BiologicalDataItem indexItem = new BiologicalDataItem();
            indexItem.setCreatedDate(new Date());
            indexItem.setPath(request.getIndexPath());
            indexItem.setFormat(BiologicalDataItemFormat.VCF_INDEX);
            indexItem.setType(BiologicalDataItemResourceType.GA4GH);
            indexItem.setName("");
            indexItem.setCreatedBy(AuthUtils.getCurrentUserId());

            vcfFile.setIndex(indexItem);
        }
        LOGGER.info(getMessage(MessagesConstants.INFO_GENE_REGISTER, vcfFile.getId(), vcfFile.getPath()));
        return vcfFile;
    }

    private Map<String, Integer> getSampleNameToOffset(final List<CallSet> callSets) {
        HashMap<String, Integer> map = new HashMap<>();
        for (CallSet callSet : callSets) {
            String sample = callSet.getId();
            int index = sample.indexOf('-');
            if (index == -1) {
                Assert.isTrue(false, "SampleId error");
            }
            int sampleId = Integer.parseInt(sample.substring(index + 1, sample.length()));
            map.put(callSet.getSampleId(), sampleId);
        }
        return map;
    }

    private VcfFile createVcfFile(final IndexedFileRegistrationRequest request,
            final FeatureReader<VariantContext> reader) {
        VcfFile vcfFile;
        vcfFile = new VcfFile();

        VCFHeader header = (VCFHeader) reader.getHeader();
        Map<String, Integer> sampleMap = header.getSampleNameToOffset();

        if (sampleMap != null && !sampleMap.isEmpty()) {
            List<VcfSample> samples = sampleMap.entrySet().stream().map(e -> new VcfSample(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            vcfFile.setSamples(samples);
        }

        BiologicalDataItemResourceType resourceType = BiologicalDataItemResourceType.translateRequestType(
            request.getType());
        String fileName = FilenameUtils.getName(request.getPath());

        vcfFile.setCompressed(resourceType == BiologicalDataItemResourceType.FILE && IOHelper.isGZIPFile(fileName));
        vcfFile.setName(request.getName() != null ? request.getName() : fileName);
        vcfFile.setId(vcfFileManager.createVcfFileId());
        vcfFile.setPath(request.getPath());
        vcfFile.setType(resourceType);
        vcfFile.setCreatedDate(new Date());
        vcfFile.setCreatedBy(AuthUtils.getCurrentUserId());
        vcfFile.setReferenceId(request.getReferenceId());

        if (StringUtils.isNotBlank(request.getIndexPath())) {
            BiologicalDataItem indexItem = new BiologicalDataItem();
            indexItem.setCreatedDate(new Date());
            indexItem.setPath(request.getIndexPath());
            indexItem.setFormat(BiologicalDataItemFormat.VCF_INDEX);
            indexItem.setType(BiologicalDataItemResourceType.translateRequestType(request.getIndexType()));
            indexItem.setName(vcfFile.getName() + "_index");
            indexItem.setCreatedBy(AuthUtils.getCurrentUserId());

            vcfFile.setIndex(indexItem);
        }

        LOGGER.info(getMessage(MessagesConstants.INFO_GENE_REGISTER, vcfFile.getId(), vcfFile.getPath()));
        return vcfFile;
    }

    @NotNull private VcfFile getVcfFileFromGA4GH(IndexedFileRegistrationRequest request,
            String requestPath) {
        VcfFile vcfFile;
        vcfFile = createVcfGA4GH(request);
        BiologicalDataItem indexItem = new BiologicalDataItem();
        indexItem.setCreatedDate(new Date());
        indexItem.setPath(requestPath);
        indexItem.setFormat(BiologicalDataItemFormat.VCF_INDEX);
        indexItem.setType(BiologicalDataItemResourceType.GA4GH);
        indexItem.setName("");
        indexItem.setCreatedBy(AuthUtils.getCurrentUserId());
        vcfFile.setIndex(indexItem);
        return vcfFile;
    }

    private VcfFile downloadVcfFile(IndexedFileRegistrationRequest request, String requestPath,
            Map<String, Chromosome> chromosomeMap, Reference reference, boolean doIndex) {

        final File newFile;
        try {
            newFile = downloadFileManager.downloadFromURL(request.getPath());
        } catch (IOException e) {
            throw new RegistrationException(getMessage(ERROR_REGISTER_FILE, request.getName()), e);
        }
        request.setIndexPath(null);
        request.setName(request.getName() != null ? request.getName() : FilenameUtils.getBaseName(requestPath));
        request.setPath(newFile.getPath());
        return createVcfFromFile(request, chromosomeMap, reference, doIndex);
    }

    private void checkSorted(VcfFile vcfFile, VariantContext variantContext, VariantContext lastFeature) {
        if (lastFeature != null && variantContext.getStart() < lastFeature.getStart() &&
                lastFeature.getContig().equals(variantContext.getContig())) {
            throw new TribbleException.MalformedFeatureFile("Input file is not sorted by start position. \n" +
                    "We saw a record with a start of " + variantContext.getContig()
                    + ":" + variantContext.getStart()
                    + " after a record with a start of "
                    + lastFeature.getContig() + ":" + lastFeature
                    .getStart(), vcfFile.getName());
        }
    }

    private Integer getSampleIndex(Long sampleId, VcfFile vcfFile) {
        if (vcfFile.getSamples() != null && !vcfFile.getSamples().isEmpty()) {
            Map<Long, VcfSample> sampleMap = new LinkedHashMap<>();
            vcfFile.getSamples().forEach(s -> sampleMap.put(s.getId(), s));

            if (sampleId != null) {
                return sampleMap.get(sampleId).getIndex();
            } else {
                return sampleMap.get(sampleMap.keySet().iterator().next()).getIndex();
            }
        }
        return null;
    }

    private Set<InfoItem> scourFilterList(Collection<InfoItem> list, List<String> whiteList) {
        return list.stream().filter(s -> whiteList.contains(s.getName())).collect(Collectors.toSet());
    }
}
