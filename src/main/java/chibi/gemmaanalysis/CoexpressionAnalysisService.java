/*
 * The Gemma project
 * 
 * Copyright (c) 2008 Columbia University
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package chibi.gemmaanalysis;

import hep.aida.ref.Histogram1D;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import ubic.basecode.dataStructure.matrix.DenseDouble3dMatrix;
import ubic.basecode.dataStructure.matrix.DenseDoubleMatrix;
import ubic.basecode.dataStructure.matrix.DoubleMatrix;
import ubic.basecode.io.reader.HistogramReader;
import ubic.basecode.io.writer.HistogramWriter;
import ubic.basecode.math.CorrelationStats;
import ubic.basecode.math.Distance;
import ubic.basecode.math.distribution.HistogramSampler;
import ubic.basecode.math.metaanalysis.CorrelationEffectMetaAnalysis;
import ubic.gemma.analysis.preprocess.filter.ExpressionExperimentFilter;
import ubic.gemma.analysis.preprocess.filter.FilterConfig;
import ubic.gemma.datastructure.matrix.ExpressionDataDoubleMatrix;
import ubic.gemma.model.expression.arrayDesign.ArrayDesign;
import ubic.gemma.model.expression.arrayDesign.ArrayDesignService;
import ubic.gemma.model.expression.bioAssayData.DesignElementDataVectorService;
import ubic.gemma.model.expression.bioAssayData.ProcessedExpressionDataVector;
import ubic.gemma.model.expression.designElement.CompositeSequence;
import ubic.gemma.model.expression.designElement.CompositeSequenceService;
import ubic.gemma.model.expression.experiment.BioAssaySet;
import ubic.gemma.model.expression.experiment.ExpressionExperiment;
import ubic.gemma.expression.experiment.service.ExpressionExperimentService;
import ubic.gemma.model.genome.Gene;
import ubic.gemma.util.Settings;
import cern.colt.list.DoubleArrayList;

/**
 * Coexpression analysis
 * <p>
 * TODO DOCUMENT ME
 * 
 * @author Raymond
 * @version $Id$
 */
public class CoexpressionAnalysisService {
    protected static final int MIN_NUM_USED = 5;

    private static final int NUM_HISTOGRAM_SAMPLES = 10000;

    private static final int NUM_HISTOGRAM_BINS = 2000;

    private static Log log = LogFactory.getLog( CoexpressionAnalysisService.class.getName() );

    private ExpressionExperimentService eeService;

    private DesignElementDataVectorService dedvService;

    private CorrelationEffectMetaAnalysis metaAnalysis;

    private ArrayDesignService adService;

    /**
     * @param csService the csService to set
     */
    protected void setCsService( CompositeSequenceService csService ) {
        this.csService = csService;
    }

    private CompositeSequenceService csService;

    /**
     * Create an effect size service
     */
    public CoexpressionAnalysisService() {
        metaAnalysis = new CorrelationEffectMetaAnalysis( true, false );
    }

    /**
     * Create and populate the coexpression matrices (correlation matrix, sample size matrix, expression level matrix)
     * 
     * @param ees
     * @param queryGenes
     * @param targetGenes
     * @param filterConfig
     * @param correlationMethod
     * @return
     */
    public CoexpressionMatrices calculateCoexpressionMatrices( Collection<BioAssaySet> ees,
            Collection<Gene> queryGenes, Collection<Gene> targetGenes, FilterConfig filterConfig,
            CorrelationMethod correlationMethod ) {
        if ( correlationMethod == null ) correlationMethod = CorrelationMethod.PEARSON;
        CoexpressionMatrices matrices = new CoexpressionMatrices( ees, queryGenes, targetGenes );
        DenseDouble3dMatrix<Gene, Gene, BioAssaySet> correlationMatrix = matrices.getCorrelationMatrix();
        DenseDouble3dMatrix<Gene, Gene, BioAssaySet> sampleSizeMatrix = matrices.getSampleSizeMatrix();
        int count = 1;
        int numEes = ees.size();
        // calculate correlations
        log.info( "Calculating correlation and sample size matrices" );
        StopWatch watch = new StopWatch();
        watch.start();
        for ( BioAssaySet bas : ees ) {
            ExpressionExperiment ee = ( ExpressionExperiment ) bas;
            log.info( "Processing " + ee.getShortName() + " (" + count++ + " of " + numEes + ")" );
            int slice = correlationMatrix.getSliceIndexByName( ee );

            // get all the composite sequences
            Collection<ArrayDesign> ads = eeService.getArrayDesignsUsed( ee );
            Collection<CompositeSequence> css = new HashSet<CompositeSequence>();
            for ( ArrayDesign ad : ads ) {
                css.addAll( adService.getCompositeSequences( ad ) );
            }
            Map<Gene, Collection<CompositeSequence>> gene2css = getGene2CsMap( css );

            ExpressionDataDoubleMatrix dataMatrix = getExpressionDataMatrix( ee, filterConfig );
            if ( dataMatrix == null ) {
                log.error( "ERROR: cannot process " + ee.getShortName() );
                continue;
            }
            for ( Gene qGene : queryGenes ) {
                int row = correlationMatrix.getRowIndexByName( qGene );
                for ( Gene tGene : targetGenes ) {
                    int col = correlationMatrix.getColIndexByName( tGene );
                    Collection<CompositeSequence> queryCss = gene2css.get( qGene );
                    Collection<CompositeSequence> targetCss = gene2css.get( tGene );

                    if ( queryCss != null && targetCss != null ) {
                        CorrelationSampleSize corr = calculateCorrelation( queryCss, targetCss, dataMatrix,
                                correlationMethod );
                        if ( corr != null ) {
                            correlationMatrix.set( slice, row, col, corr.correlation );
                            sampleSizeMatrix.set( slice, row, col, corr.sampleSize );
                        }
                    }
                }
            }
        }
        watch.stop();
        log.info( "Calculated correlations of all " + numEes + " in " + watch );
        return matrices;
    }

    /**
     * Calculate an effect size matrix
     * 
     * @param correlationMatrix
     * @param sampleSizeMatrix
     * @return
     */
    public DoubleMatrix<Gene, Gene> calculateEffectSizeMatrix(
            DenseDouble3dMatrix<Gene, Gene, BioAssaySet> correlationMatrix,
            DenseDouble3dMatrix<Gene, Gene, BioAssaySet> sampleSizeMatrix ) {
        DoubleMatrix<Gene, Gene> matrix = new DenseDoubleMatrix<Gene, Gene>( correlationMatrix.rows(),
                correlationMatrix.columns() );
        matrix.setRowNames( correlationMatrix.getRowNames() );
        matrix.setColumnNames( correlationMatrix.getColNames() );

        for ( Gene rowId : correlationMatrix.getRowNames() ) {
            int rowIndex = matrix.getRowIndexByName( rowId );
            for ( Gene colId : correlationMatrix.getColNames() ) {
                int colIndex = matrix.getColIndexByName( colId );
                DoubleArrayList correlations = new DoubleArrayList( correlationMatrix.slices() );
                DoubleArrayList sampleSizes = new DoubleArrayList( correlationMatrix.slices() );
                for ( BioAssaySet bas : correlationMatrix.getSliceNames() ) {
                    ExpressionExperiment sliceId = ( ExpressionExperiment ) bas;
                    int sliceIndex = correlationMatrix.getSliceIndexByName( sliceId );
                    double correlation = correlationMatrix.get( sliceIndex, rowIndex, colIndex );
                    double sampleSize = sampleSizeMatrix.get( sliceIndex, rowIndex, colIndex );
                    correlations.add( correlation );
                    sampleSizes.add( sampleSize );
                }
                metaAnalysis.run( correlations, sampleSizes );
                double effectSize = metaAnalysis.getE();
                matrix.set( rowIndex, colIndex, effectSize );
            }
        }
        return matrix;
    }

    /**
     * Calculate the p-values for a max correlation matrix using empirical distributions stored in the gemmaData dir
     * 
     * @param maxCorrelationMatrix
     * @param n specifies which n'th maximum value of the sample to be taken
     * @param ees expression experiments to sample from the gemmaData dir
     * @return p-value matrix
     */
    public DoubleMatrix<String, String> calculateMaxCorrelationPValueMatrix(
            DoubleMatrix<String, String> maxCorrelationMatrix, int n, Collection<BioAssaySet> ees ) {
        log.info( "Calculating " + n + "-max p-value matrix" );
        StopWatch watch = new StopWatch();
        watch.start();
        DoubleMatrix<String, String> pMatrix = new DenseDoubleMatrix<String, String>( maxCorrelationMatrix.rows(),
                maxCorrelationMatrix.columns() );
        pMatrix.setRowNames( maxCorrelationMatrix.getRowNames() );
        pMatrix.setColumnNames( maxCorrelationMatrix.getColNames() );

        // fill matrix with NaNs
        for ( int i = 0; i < pMatrix.rows(); i++ )
            for ( int j = 0; j < pMatrix.columns(); j++ )
                pMatrix.set( i, j, Double.NaN );

        // fill a histogram with the empirical distribution of max correlations
        Histogram1D hist = new Histogram1D( "Max correlation empirical distribution", NUM_HISTOGRAM_BINS, -1d, 1d );
        Collection<HistogramSampler> histSamplers = getHistogramSamplers( ees );
        for ( int i = 0; i < NUM_HISTOGRAM_SAMPLES; i++ ) {
            DoubleArrayList samples = new DoubleArrayList( histSamplers.size() );
            for ( HistogramSampler sampler : histSamplers ) {
                samples.add( sampler.nextSample() );
            }
            samples.sort();
            if ( samples.size() > n ) hist.fill( samples.get( samples.size() - 1 - n ) );
        }

        HistogramWriter out = new HistogramWriter();
        try {
            out.write( hist, new FileWriter( "hist.txt" ) );
        } catch ( IOException e ) {
        }

        // calculate the p-value
        for ( int i = 0; i < maxCorrelationMatrix.rows(); i++ ) {
            for ( int j = 0; j < maxCorrelationMatrix.columns(); j++ ) {
                double corr = maxCorrelationMatrix.get( i, j );
                if ( Double.isNaN( corr ) || corr == 0d )
                    pMatrix.set( i, j, Double.NaN );
                else {
                    double pVal = getPvalue( hist, corr );
                    pMatrix.set( i, j, pVal );
                }
            }
        }
        watch.stop();
        log.info( "Finished calculating " + n + "-max p-value matrix in " + watch );
        return pMatrix;
    }

    /**
     * Filter the specified matrix so columns (expression experiments) of missing data are removed
     * 
     * @param matrix
     * @return
     */
    public DenseDouble3dMatrix<Gene, Gene, ExpressionExperiment> filterCoexpressionMatrix(
            DenseDouble3dMatrix<Gene, Gene, ExpressionExperiment> matrix ) {
        log.info( "Filtering expression experiments..." );
        // find empty columns
        List<ExpressionExperiment> filteredEeIds = new ArrayList<ExpressionExperiment>();
        EE: for ( ExpressionExperiment eeId : matrix.getSliceNames() ) {
            int slice = matrix.getSliceIndexByName( eeId );
            for ( int i = 0; i < matrix.rows(); i++ )
                for ( int j = 0; j < matrix.columns(); j++ )
                    if ( !matrix.isMissing( slice, i, j ) ) {
                        filteredEeIds.add( eeId );
                        continue EE;
                    }
        }
        log.info( filteredEeIds.size() + " of " + matrix.slices() + " passed" );

        // create a new filtered matrix
        DenseDouble3dMatrix<Gene, Gene, ExpressionExperiment> filteredMatrix = new DenseDouble3dMatrix<Gene, Gene, ExpressionExperiment>(
                filteredEeIds.size(), matrix.rows(), matrix.columns() );
        filteredMatrix.setSliceNames( filteredEeIds );
        for ( int i = 0; i < filteredEeIds.size(); i++ ) {
            ExpressionExperiment eeId = filteredEeIds.get( i );
            int slice = filteredMatrix.getSliceIndexByName( eeId );
            for ( int j = 0; j < matrix.rows(); j++ ) {
                for ( int k = 0; k < matrix.columns(); k++ ) {
                    double val = matrix.get( matrix.getSliceIndexByName( eeId ), j, k );
                    filteredMatrix.set( slice, j, k, val );
                }
            }
        }

        return filteredMatrix;

    }

    public ArrayDesignService getAdService() {
        return adService;
    }

    /**
     * Get expression data matrix for the specified expression experiment
     * 
     * @param ee
     * @param filterConfig
     * @return an expression data double matrix
     */
    public ExpressionDataDoubleMatrix getExpressionDataMatrix( ExpressionExperiment ee, FilterConfig filterConfig ) {
        StopWatch watch = new StopWatch();
        watch.start();
        log.info( ee.getShortName() + ": Getting expression data matrix" );

        // get dedvs to build expression data matrix
        Collection<ProcessedExpressionDataVector> dedvs = eeService.getProcessedDataVectors( ee );
        dedvService.thaw( dedvs );

        // build and filter expression data matrix
        Collection<ArrayDesign> arrayDesignsUsed = eeService.getArrayDesignsUsed( ee );
        ExpressionExperimentFilter filter = new ExpressionExperimentFilter( arrayDesignsUsed, filterConfig );
        ExpressionDataDoubleMatrix eeDoubleMatrix;
        try {
            eeDoubleMatrix = filter.getFilteredMatrix( dedvs );
        } catch ( Exception e ) {
            log.error( e.getMessage() );
            return null;
        }
        watch.stop();
        log.info( "Retrieved expression data matrix in " + watch );
        return eeDoubleMatrix;
    }

    /**
     * Get a gene to composite sequence map // FIXME This corresponds to an existing service method?
     * 
     * @param css
     * @return gene to composite sequences map
     */
    public Map<Gene, Collection<CompositeSequence>> getGene2CsMap( Collection<CompositeSequence> css ) {
        Map<CompositeSequence, Collection<Gene>> cs2gene = csService.getGenes( css );
        // filter for specific cs 2 gene
        for ( Iterator<Map.Entry<CompositeSequence, Collection<Gene>>> it = cs2gene.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<CompositeSequence, Collection<Gene>> entry = it.next();
            Collection<Gene> genes = entry.getValue();
            if ( genes.size() > 1 ) it.remove();
        }

        Map<Gene, Collection<CompositeSequence>> gene2css = new HashMap<Gene, Collection<CompositeSequence>>();
        for ( Map.Entry<CompositeSequence, Collection<Gene>> entry : cs2gene.entrySet() ) {
            CompositeSequence cs = entry.getKey();
            Collection<Gene> genes = entry.getValue();
            for ( Gene gene : genes ) {
                Collection<CompositeSequence> c = gene2css.get( gene );
                if ( c == null ) {
                    c = new HashSet<CompositeSequence>();
                    gene2css.put( gene, c );
                }
                c.add( cs );
            }
        }
        return gene2css;
    }

    /**
     * Get the histogram samplers for the specified expression experiments
     * 
     * @param ees
     * @return a collection of histogram samplers
     */
    public Collection<HistogramSampler> getHistogramSamplers( Collection<BioAssaySet> ees ) {
        Collection<HistogramSampler> histSamplers = new HashSet<HistogramSampler>();
        for ( BioAssaySet bas : ees ) {
            ExpressionExperiment ee = ( ExpressionExperiment ) bas;
            String fileName = Settings.getAnalysisStoragePath() + ee.getShortName() + ".correlDist.txt";
            try {
                HistogramSampler sampler = readHistogramFile( fileName );
                if ( sampler == null )
                    log.error( "ERROR: " + ee.getShortName() + " has an invalid correlation distribution" );
                else
                    histSamplers.add( sampler );
            } catch ( IOException e ) {
                log.error( e.getMessage() );
                log.error( "ERROR: Unable to read correlation distribution file for " + ee.getShortName() );
            }
        }
        return histSamplers;
    }

    /**
     * Fold the 3D correlation matrix to a 2D matrix with maximum correlations
     * 
     * @param matrix - correlation matrix
     * @param n - the Nth largest correlation
     * @return matrix with Nth largest correlations
     */
    public DoubleMatrix<Gene, Gene> getMaxCorrelationMatrix(
            DenseDouble3dMatrix<Gene, Gene, ExpressionExperiment> matrix, int n ) {
        log.info( "Calculating " + n + "-max matrix" );
        StopWatch watch = new StopWatch();
        watch.start();
        DoubleMatrix<Gene, Gene> maxMatrix = new DenseDoubleMatrix<Gene, Gene>( matrix.rows(), matrix.columns() );
        maxMatrix.setRowNames( matrix.getRowNames() );
        maxMatrix.setColumnNames( matrix.getColNames() );
        for ( int i = 0; i < matrix.rows(); i++ ) {
            for ( int j = 0; j < matrix.columns(); j++ ) {
                DoubleArrayList list = new DoubleArrayList();
                for ( int k = 0; k < matrix.slices(); k++ ) {
                    double val = matrix.get( k, i, j );
                    if ( !Double.isNaN( val ) ) {
                        list.add( val );
                    }
                }
                list.sort();
                double val = Double.NaN;
                if ( list.size() > n ) val = list.get( list.size() - 1 - n );
                maxMatrix.set( i, j, val );
            }
        }
        watch.stop();
        log.info( "Finished calculating " + n + "-max matrix in " + watch );
        return maxMatrix;
    }

    public void setAdService( ArrayDesignService adService ) {
        this.adService = adService;
    }

    public void setDedvService( DesignElementDataVectorService dedvService ) {
        this.dedvService = dedvService;
    }

    public void setEeService( ExpressionExperimentService eeService ) {
        this.eeService = eeService;
    }

    /**
     * Calculates all pairwise correlations between the query and target composite sequences and then takes the median
     * correlation
     * 
     * @param queryCss
     * @param targetCss
     * @param dataMatrix
     * @return
     */
    private CorrelationSampleSize calculateCorrelation( Collection<CompositeSequence> queryCss,
            Collection<CompositeSequence> targetCss, ExpressionDataDoubleMatrix dataMatrix, CorrelationMethod method ) {
        TreeMap<Double, Double> correlNumUsedMap = new TreeMap<Double, Double>();
        // calculate all pairwise correlations between cs groups
        for ( CompositeSequence queryCs : queryCss ) {
            for ( CompositeSequence targetCs : targetCss ) {
                Double[] queryVals = dataMatrix.getRow( queryCs );
                Double[] targetVals = dataMatrix.getRow( targetCs );
                if ( queryVals != null && targetVals != null ) {
                    double[] v1 = new double[queryVals.length];
                    double[] v2 = new double[targetVals.length];
                    for ( int i = 0; i < queryVals.length; i++ ) {
                        if ( queryVals[i] != null )
                            v1[i] = queryVals[i];
                        else
                            v1[i] = Double.NaN;
                    }
                    for ( int i = 0; i < targetVals.length; i++ ) {
                        if ( targetVals[i] != null )
                            v2[i] = targetVals[i];
                        else
                            v2[i] = Double.NaN;
                    }

                    int numUsed = 0;
                    for ( int i = 0; i < v1.length && i < v2.length; i++ )
                        if ( !Double.isNaN( v1[i] ) && !Double.isNaN( v2[i] ) ) numUsed++;
                    if ( numUsed > MIN_NUM_USED ) {
                        double correlation;
                        switch ( method ) {
                            case SPEARMAN:
                                correlation = Distance.spearmanRankCorrelation( new DoubleArrayList( v1 ),
                                        new DoubleArrayList( v2 ) );
                                break;
                            case PEARSON:
                            default:
                                correlation = CorrelationStats.correl( v1, v2 );

                        }
                        correlNumUsedMap.put( correlation, ( double ) numUsed );
                    }
                }
            }
        }
        if ( correlNumUsedMap.size() == 0 ) {
            return null;
        }
        List<Double> correlations = new ArrayList<Double>( correlNumUsedMap.keySet() );
        // take the median correlation
        Double correlation = correlations.get( correlations.size() / 2 );
        Double sampleSize = correlNumUsedMap.get( correlation );
        CorrelationSampleSize c = new CorrelationSampleSize();
        c.correlation = correlation;
        c.sampleSize = sampleSize;
        return c;

    }

    /**
     * Calculates a p-value from a histogram
     * 
     * @param histogram
     * @param x
     * @return
     */
    private double getPvalue( Histogram1D histogram, double x ) {
        int bin = histogram.xAxis().coordToIndex( x );
        double sum = 0.0d;
        for ( int i = 0; i <= bin; i++ ) {
            sum += histogram.binHeight( i );
        }
        if ( sum == 0d ) return 0d;

        return sum / NUM_HISTOGRAM_SAMPLES;
    }

    /**
     * Read a correlation distribution
     * 
     * @param fileName
     * @return a histogram sampler for the read distribution
     * @throws IOException
     */
    private HistogramSampler readHistogramFile( String fileName ) throws IOException {
        HistogramReader in = new HistogramReader( fileName );
        Histogram1D hist = in.read1D();
        HistogramSampler sampler = new HistogramSampler( hist );
        return sampler;
    }

    /**
     * Stores matrices related to coexpression analysis
     * 
     * @author raymond
     */
    public class CoexpressionMatrices {
        private DenseDouble3dMatrix<Gene, Gene, BioAssaySet> correlationMatrix;

        private DenseDouble3dMatrix<Gene, Gene, BioAssaySet> sampleSizeMatrix;

        private Map<ExpressionExperiment, String> eeNameMap;

        private Map<Gene, String> geneNameMap;

        /**
         * @param ees
         * @param queryGenes
         * @param targetGenes
         */
        public CoexpressionMatrices( Collection<BioAssaySet> ees, Collection<Gene> queryGenes,
                Collection<Gene> targetGenes ) {
            List<BioAssaySet> eeList = new ArrayList<BioAssaySet>( ees );
            List<Gene> qGeneList = new ArrayList<Gene>( queryGenes );
            List<Gene> tGeneList = new ArrayList<Gene>( targetGenes );

            correlationMatrix = new DenseDouble3dMatrix<Gene, Gene, BioAssaySet>( eeList, qGeneList, tGeneList );
            sampleSizeMatrix = new DenseDouble3dMatrix<Gene, Gene, BioAssaySet>( eeList, qGeneList, tGeneList );
            // NaN matrices
            for ( int k = 0; k < correlationMatrix.slices(); k++ ) {
                for ( int i = 0; i < correlationMatrix.rows(); i++ ) {
                    for ( int j = 0; j < correlationMatrix.columns(); j++ ) {
                        correlationMatrix.set( k, i, j, Double.NaN );
                        sampleSizeMatrix.set( k, i, j, Double.NaN );
                    }
                }
            }

            // generate name maps
            eeNameMap = new HashMap<ExpressionExperiment, String>();
            for ( BioAssaySet bas : ees ) {
                ExpressionExperiment ee = ( ExpressionExperiment ) bas;
                eeNameMap.put( ee, ee.getShortName() );
            }

            geneNameMap = new HashMap<Gene, String>();
            for ( Gene gene : queryGenes ) {
                String name = gene.getOfficialSymbol();
                if ( name == null ) name = gene.getId().toString();
                geneNameMap.put( gene, name );
            }
            for ( Gene gene : targetGenes ) {
                String name = gene.getOfficialSymbol();
                if ( name == null ) name = gene.getId().toString();
                geneNameMap.put( gene, name );
            }
        }

        public DenseDouble3dMatrix<Gene, Gene, BioAssaySet> getCorrelationMatrix() {
            return correlationMatrix;
        }

        public Map<ExpressionExperiment, String> getEeNameMap() {
            return eeNameMap;
        }

        public Map<Gene, String> getGeneNameMap() {
            return geneNameMap;
        }

        public DenseDouble3dMatrix<Gene, Gene, BioAssaySet> getSampleSizeMatrix() {
            return sampleSizeMatrix;
        }

        public void setCorrelationMatrix( DenseDouble3dMatrix<Gene, Gene, BioAssaySet> correlationMatrix ) {
            this.correlationMatrix = correlationMatrix;
        }

        public void setSampleSizeMatrix( DenseDouble3dMatrix<Gene, Gene, BioAssaySet> sampleSizeMatrix ) {
            this.sampleSizeMatrix = sampleSizeMatrix;
        }
    }

    public static enum CorrelationMethod {
        SPEARMAN, PEARSON
    }

    /**
     * Stores pairs of genes
     * 
     * @author raymond
     */
    public class GenePair {
        private Gene gene1;

        private Gene gene2;

        public GenePair( Gene gene1, Gene gene2 ) {
            this.gene1 = gene1;
            this.gene2 = gene2;
        }

        @Override
        public String toString() {
            String s1 = gene1.getOfficialSymbol();
            String s2 = gene2.getOfficialSymbol();
            if ( s1 == null ) s1 = gene1.getId().toString();
            if ( s2 == null ) s2 = gene2.getId().toString();
            return s1 + ":" + s2;
        }
    }

    private class CorrelationSampleSize {
        double correlation;

        double sampleSize;
    }

}
