/*
 * Copyright (c) 2014-2015, Bolotin Dmitry, Chudakov Dmitry, Shugay Mikhail
 * (here and after addressed as Inventors)
 * All Rights Reserved
 *
 * Permission to use, copy, modify and distribute any part of this program for
 * educational, research and non-profit purposes, by non-profit institutions
 * only, without fee, and without a written agreement is hereby granted,
 * provided that the above copyright notice, this paragraph and the following
 * three paragraphs appear in all copies.
 *
 * Those desiring to incorporate this work into commercial products or use for
 * commercial purposes should contact the Inventors using one of the following
 * email addresses: chudakovdm@mail.ru, chudakovdm@gmail.com
 *
 * IN NO EVENT SHALL THE INVENTORS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 * SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 * ARISING OUT OF THE USE OF THIS SOFTWARE, EVEN IF THE INVENTORS HAS BEEN
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * THE SOFTWARE PROVIDED HEREIN IS ON AN "AS IS" BASIS, AND THE INVENTORS HAS
 * NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 * MODIFICATIONS. THE INVENTORS MAKES NO REPRESENTATIONS AND EXTENDS NO
 * WARRANTIES OF ANY KIND, EITHER IMPLIED OR EXPRESS, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A
 * PARTICULAR PURPOSE, OR THAT THE USE OF THE SOFTWARE WILL NOT INFRINGE ANY
 * PATENT, TRADEMARK OR OTHER RIGHTS.
 */
package com.milaboratory.mixcr.vdjaligners;

import com.milaboratory.core.Range;
import com.milaboratory.core.Target;
import com.milaboratory.core.alignment.Alignment;
import com.milaboratory.core.alignment.AlignmentUtils;
import com.milaboratory.core.alignment.batch.AlignmentHit;
import com.milaboratory.core.alignment.batch.AlignmentResult;
import com.milaboratory.core.io.sequence.PairedRead;
import com.milaboratory.core.mutations.Mutations;
import com.milaboratory.core.sequence.NucleotideSequence;
import com.milaboratory.mixcr.basictypes.HasGene;
import com.milaboratory.mixcr.basictypes.VDJCAlignments;
import com.milaboratory.mixcr.basictypes.VDJCHit;
import com.milaboratory.util.BitArray;
import io.repseq.core.*;

import java.util.*;

public final class VDJCAlignerPVFirst extends VDJCAlignerAbstract<PairedRead> {
    private static final ReferencePoint reqPointR = ReferencePoint.CDR3End.move(3);
    private static final ReferencePoint reqPointL = ReferencePoint.CDR3Begin.move(-3);

    public VDJCAlignerPVFirst(VDJCAlignerParameters parameters) {
        super(parameters);
    }

    @Override
    protected VDJCAlignmentResult<PairedRead> process0(final PairedRead input) {
        ensureInitialized();

        Target[] targets = getTargets(input);

        // Creates helper classes for each PTarget
        PAlignmentHelper[] helpers = createInitialHelpers(targets);

        // Main alignment logic
        for (PAlignmentHelper helper : helpers) {
            if (helper.hasVHits())
                // Sorting and filtering hits with low V-end (FR3, CDR3) score
                helper.sortAndFilterBasedOnVEndScore();

            // Perform J alignments
            helper.performJAlignment();

            // Perform filtering of chimeric hits if needed
            helper.performChainFilteringIfNeeded();
        }

        return parameters.getAllowPartialAlignments() ?
                processPartial(input, helpers) :
                processStrict(input, helpers);
    }

    private VDJCAlignmentResult<PairedRead> processPartial(PairedRead input, PAlignmentHelper[] helpers) {
        // Calculates which PTarget was aligned with the highest score
        PAlignmentHelper bestHelper = helpers[0];
        helpers[0].performCDAlignment();
        for (int i = 1; i < helpers.length; ++i) {
            helpers[i].performCDAlignment();
            if (bestHelper.score() < helpers[i].score())
                bestHelper = helpers[i];
        }

        if (!bestHelper.hasVOrJHits()) {
            onFailedAlignment(input, VDJCAlignmentFailCause.NoHits);
            return new VDJCAlignmentResult<>(input);
        }

        // Calculates if this score is bigger then the threshold
        if (bestHelper.score() < parameters.getMinSumScore()) {
            onFailedAlignment(input, VDJCAlignmentFailCause.LowTotalScore);
            return new VDJCAlignmentResult<>(input);
        }

        // Finally filtering hits inside this helper to meet minSumScore and maxHits limits
        bestHelper.filterHits(parameters.getMinSumScore(), parameters.getMaxHits());

        // TODO do we really need this ?
        if (!bestHelper.hasVOrJHits()) {
            onFailedAlignment(input, VDJCAlignmentFailCause.LowTotalScore);
            return new VDJCAlignmentResult<>(input);
        }

        VDJCAlignments alignments = bestHelper.createResult(input.getId(), this);

        // Final check
        if (!parameters.getAllowNoCDR3PartAlignments()) {
            // CDR3 Begin / End
            boolean containCDR3Edge = false;
            for (int i = 0; i < 2; i++)
                if (alignments.getPartitionedTarget(i).getPartitioning().isAvailable(reqPointL)
                        || alignments.getPartitionedTarget(i).getPartitioning().isAvailable(reqPointR)) {
                    containCDR3Edge = true;
                    break;
                }

            if (!containCDR3Edge) {
                onFailedAlignment(input, VDJCAlignmentFailCause.NoCDR3Parts);
                return new VDJCAlignmentResult<>(input);
            }
        }

        // Read successfully aligned

        onSuccessfulAlignment(input, alignments);

        return new VDJCAlignmentResult<>(input, alignments);
    }

    private VDJCAlignmentResult<PairedRead> processStrict(PairedRead input, PAlignmentHelper[] helpers) {
        // Calculates which PTarget was aligned with the highest score
        PAlignmentHelper bestHelper = helpers[0];
        for (int i = 1; i < helpers.length; ++i)
            if (bestHelper.score() < helpers[i].score())
                bestHelper = helpers[i];

        // If V or J hits are absent
        if (!bestHelper.hasVAndJHits()) {
            if (!bestHelper.hasVOrJHits())
                onFailedAlignment(input, VDJCAlignmentFailCause.NoHits);
            else if (!bestHelper.hasVHits())
                onFailedAlignment(input, VDJCAlignmentFailCause.NoVHits);
            else
                onFailedAlignment(input, VDJCAlignmentFailCause.NoJHits);
            return new VDJCAlignmentResult<>(input);
        }

        // Performing alignment of C and D genes; if corresponding parameters are set include their scores to
        // the total score value
        bestHelper.performCDAlignment();

        // Calculates if this score is bigger then the threshold
        if (bestHelper.score() < parameters.getMinSumScore()) {
            onFailedAlignment(input, VDJCAlignmentFailCause.LowTotalScore);
            return new VDJCAlignmentResult<>(input);
        }

        // Finally filtering hits inside this helper to meet minSumScore and maxHits limits
        bestHelper.filterHits(parameters.getMinSumScore(), parameters.getMaxHits());

        // If hits for V or J are missing after filtration
        if (!bestHelper.isGoodVJ()) {
            onFailedAlignment(input, VDJCAlignmentFailCause.LowTotalScore);
            return new VDJCAlignmentResult<>(input);
        }

        // Read successfully aligned

        VDJCAlignments alignments = bestHelper.createResult(input.getId(), this);

        onSuccessfulAlignment(input, alignments);

        return new VDJCAlignmentResult<>(input, alignments);
    }

    Target[] getTargets(PairedRead read) {
        return parameters.getReadsLayout().createTargets(read);
    }

    PAlignmentHelper[] createInitialHelpers(Target[] target) {
        PAlignmentHelper[] result = new PAlignmentHelper[target.length];
        for (int i = 0; i < target.length; i++)
            result[i] = createInitialHelper(target[i]);
        return result;
    }

    PAlignmentHelper createInitialHelper(Target target) {
        return new PAlignmentHelper(target,
                vAligner.align(target.targets[0].getSequence()),
                vAligner.align(target.targets[1].getSequence())
        );
    }

    static final PreVDJCHit[] zeroArray = new PreVDJCHit[0];
    static final AlignmentHit<NucleotideSequence, VDJCGene>[] zeroKArray = new AlignmentHit[0];

    final class PAlignmentHelper {
        final Target target;
        final AlignmentResult<AlignmentHit<NucleotideSequence, VDJCGene>>[] vResults;
        AlignmentResult<AlignmentHit<NucleotideSequence, VDJCGene>>[] jResults;
        PairedHit[] vHits, jHits;
        VDJCHit[] dHits = null, cHits = null;
        //PairedHit bestVHits;

        PAlignmentHelper(Target target, AlignmentResult<AlignmentHit<NucleotideSequence, VDJCGene>>... vResults) {
            this.target = target;
            this.vResults = vResults;
            this.vHits = extractDoubleHits(vResults);
            //this.bestVHits = new PairedHit(
            //        vResults[0].getBestHit(),
            //        vResults[1].getBestHit()
            //);
        }

        void sortAndFilterBasedOnVEndScore() {
            // Calculating vEndScores
            for (PairedHit hit : vHits)
                hit.calculateVEndScore(VDJCAlignerPVFirst.this);

            // Sorting based on v-end score (score of alignment of FR3 and CDR3
            Arrays.sort(vHits, V_END_SCORE_COMPARATOR);

            // Retrieving maximal value
            float maxVEndScore = vHits[0].vEndScore;

            // Calculating threshold
            float threshold = maxVEndScore * parameters.getRelativeMinVFR3CDR3Score();

            // Filtering
            for (int i = 0; i < vHits.length; ++i)
                if (vHits[i].vEndScore < threshold) {
                    vHits = Arrays.copyOfRange(vHits, 0, i);
                    break;
                }

            // Calculate normal score for each read for further processing
            // and sort according to this score
            calculateScoreAndSort(vHits);
        }

        ///**
        // * Calculates best V hits for each read
        // */
        //void updateBestV() {
        //    AlignmentHit<NucleotideSequence, VDJCGene> hit0 = null, hit1 = null;
        //
        //    for (PairedHit hit : vHits) {
        //        if (hit.hit0 != null &&
        //                (hit0 == null ||
        //                        hit0.getAlignment().getScore() > hit.hit0.getAlignment().getScore()))
        //            hit0 = hit.hit0;
        //        if (hit.hit1 != null &&
        //                (hit1 == null ||
        //                        hit1.getAlignment().getScore() > hit.hit1.getAlignment().getScore()))
        //            hit1 = hit.hit1;
        //    }
        //
        //    // Setting best hits for current array of hits (after filtration)
        //    bestVHits = new PairedHit(hit0, hit1, true);
        //}

        boolean hasVHits() {
            return vHits != null && vHits.length > 0;
        }

        boolean hasVOrJHits() {
            return (vHits != null && vHits.length > 0) ||
                    (jHits != null && jHits.length > 0);
        }

        boolean hasVAndJHits() {
            return vHits != null && jHits != null &&
                    vHits.length > 0 && jHits.length > 0;
        }

        boolean isGoodVJ() {
            return hasVAndJHits() && hasVJOnTheSameTarget();
        }

        private boolean hasVJOnTheSameTarget() {
            for (int i = 0; i < 2; i++)
                if (vHits[0].get(i) != null && jHits[0].get(i) != null)
                    return true;
            return false;
        }

        /**
         * Converts two AlignmentResults to an array of paired hits (each paired hit for a particular V of J gene)
         */
        final PairedHit[] extractDoubleHits(AlignmentResult<AlignmentHit<NucleotideSequence, VDJCGene>>... results) {
            Map<VDJCGeneId, PairedHit> hits = new HashMap<>();
            addHits(hits, results[0], 0);
            addHits(hits, results[1], 1);

            return hits.values().toArray(new PairedHit[hits.size()]);
        }

        /**
         * Returns sum score for this targets.
         */
        float score() {
            // Adding V score
            float score = vHits.length > 0 ? vHits[0].sumScore : 0.0f;

            // Adding J score
            if (jHits != null && jHits.length > 0)
                score += jHits[0].sumScore;

            // Adding C score
            if (parameters.doIncludeCScore() && cHits != null && cHits.length > 0)
                score += cHits[0].getScore();

            // Adding D score
            if (parameters.doIncludeDScore() && dHits != null && dHits.length > 0)
                score += dHits[0].getScore();

            return score;
        }

        void addHits(Map<VDJCGeneId, PairedHit> hits,
                     AlignmentResult<AlignmentHit<NucleotideSequence, VDJCGene>> result,
                     int index) {
            if (result == null)
                return;

            for (AlignmentHit<NucleotideSequence, VDJCGene> hit : result.getHits()) {
                VDJCGeneId id = hit.getRecordPayload().getId();
                PairedHit val =
                        index == 0 ?
                                null :
                                hits.get(id);

                if (val == null)
                    hits.put(id, val = new PairedHit());

                val.set(index, hit);
            }
        }


        /**
         * Converts this object to a final VDJAlignment object.
         */
        VDJCAlignments createResult(long readId, VDJCAlignerPVFirst aligner) {
            VDJCHit[] vHits = convert(this.vHits, GeneType.Variable, aligner);
            VDJCHit[] jHits = convert(this.jHits, GeneType.Joining, aligner);

            return new VDJCAlignments(readId, vHits, dHits, jHits, cHits, target.targets);
        }

        /**
         * Preforms J alignment after V alignments are built.
         */
        @SuppressWarnings("unchecked")
        void performJAlignment() {
            jHits = extractDoubleHits(jResults = new AlignmentResult[]{
                    performJAlignment(0),
                    performJAlignment(1)
            });

            calculateScoreAndSort(jHits);
        }

        Chains getVJCommonChains() {
            return getChains(vHits).intersection(getChains(jHits));
        }

        Chains getChains(PairedHit[] hits) {
            Chains c = Chains.EMPTY;
            for (PairedHit hit : hits)
                c = c.merge(hit.getGene().getChains());
            return c;
        }

        /**
         * Filter V/J hits with common chain only
         */
        void performChainFilteringIfNeeded() {
            // Check if parameters allow chimeras
            if (parameters.isAllowChimeras())
                return;

            // Calculate common chains
            Chains commonChains = getVJCommonChains();

            if (commonChains.isEmpty())
                // Exceptional case, or partial alignment
                return;

            // Filtering V genes

            int filteredSize = 0;
            for (PairedHit hit : vHits)
                if (hit.getGene().getChains().intersects(commonChains))
                    ++filteredSize;

            // Perform filtering (new array allocation) only if needed
            if (vHits.length != filteredSize) {
                PairedHit[] newHits = new PairedHit[filteredSize];
                filteredSize = 0; // Used as pointer
                for (PairedHit hit : vHits)
                    if (hit.getGene().getChains().intersects(commonChains))
                        newHits[filteredSize++] = hit;

                assert newHits.length == filteredSize;

                vHits = newHits;
            }

            // Filtering J genes

            filteredSize = 0;
            for (PairedHit hit : jHits)
                if (hit.getGene().getChains().intersects(commonChains))
                    ++filteredSize;

            // Perform filtering (new array allocation) only if needed
            if (jHits.length != filteredSize) {
                PairedHit[] newHits = new PairedHit[filteredSize];
                filteredSize = 0; // Used as pointer
                for (PairedHit hit : jHits)
                    if (hit.getGene().getChains().intersects(commonChains))
                        newHits[filteredSize++] = hit;

                assert newHits.length == filteredSize;

                jHits = newHits;
            }
        }

        /**
         * Perform final alignment of D and C genes on fully marked-up reads (with by V and J alignments).
         */
        @SuppressWarnings("unchecked")
        void performCDAlignment() {
            PairedHit bestVHit = vHits.length == 0 ? null : vHits[0];
            PairedHit bestJHit = jHits.length == 0 ? null : jHits[0];

            if ((bestVHit == null || bestJHit == null) && !parameters.getAllowPartialAlignments())
                return;

            //Alignment of D gene
            if (singleDAligner != null) {
                PreVDJCHit[][] preDHits = new PreVDJCHit[2][];
                Arrays.fill(preDHits, zeroArray);

                if (bestVHit != null && bestJHit != null)
                    for (int i = 0; i < 2; ++i) {
                        Alignment<NucleotideSequence> vAlignment = bestVHit.get(i) == null ? null : bestVHit.get(i).getAlignment();
                        Alignment<NucleotideSequence> jAlignment = bestJHit.get(i) == null ? null : bestJHit.get(i).getAlignment();
                        if (vAlignment == null || jAlignment == null)
                            continue;
                        int from = vAlignment.getSequence2Range().getTo(),
                                to = jAlignment.getSequence2Range().getFrom();
                        if (from >= to)
                            continue;
                        List<PreVDJCHit> temp = singleDAligner.align0(target.targets[i].getSequence(),
                                getPossibleDLoci(vHits, jHits), from, to);
                        preDHits[i] = temp.toArray(new PreVDJCHit[temp.size()]);
                    }

                dHits = PreVDJCHit.combine(getDGenesToAlign(),
                        parameters.getFeatureToAlign(GeneType.Diversity), preDHits);
            }

            //Alignment of C gene
            if (cAligner != null) {
                AlignmentHit<NucleotideSequence, VDJCGene>[][] results = new AlignmentHit[2][];
                Arrays.fill(results, zeroKArray);

                boolean calculated = false;
                if (bestVHit == null && bestJHit == null)
                    calculated = true;

                if (!calculated && bestJHit != null) { // If J hit is present somewhere

                    // The following algorithm represents following behaviour:
                    // If there is a J hit in R1 search for C gene in R1 (after J) and R2
                    // If there is a J hit in R2 search for C gene in R2 (after J)

                    for (int i = 0; i < 2; ++i) {
                        Alignment<NucleotideSequence> jAlignment = bestJHit.get(i) == null ? null : bestJHit.get(i).getAlignment();
                        if (i == 0 && jAlignment == null)
                            continue;
                        int from = jAlignment == null ? 0 : jAlignment.getSequence2Range().getTo();
                        List<AlignmentHit<NucleotideSequence, VDJCGene>> temp = cAligner.align(target.targets[i].getSequence(), from,
                                target.targets[i].size(),
                                getFilter(GeneType.Constant, vHits, jHits))
                                .getHits();
                        results[i] = temp.toArray(new AlignmentHit[temp.size()]);
                    }
                    calculated = true;
                }

                if (!calculated && bestVHit.get(0) != null && bestVHit.get(1) == null) { // At least one V hit must be present in the first read

                    // Searching for C gene in second read

                    List<AlignmentHit<NucleotideSequence, VDJCGene>> temp = cAligner.align(target.targets[1].getSequence(),
                            0, target.targets[1].size(),
                            getFilter(GeneType.Constant, vHits))
                            .getHits();

                    results[1] = temp.toArray(new AlignmentHit[temp.size()]);
                }

                cHits = combine(parameters.getFeatureToAlign(GeneType.Constant), results);
            } else
                cHits = new VDJCHit[0];
        }

        /**
         * Preforms J alignment for a single read
         */
        AlignmentResult<AlignmentHit<NucleotideSequence, VDJCGene>> performJAlignment(int index) {
            // Getting best V hit
            AlignmentHit<NucleotideSequence, VDJCGene> vHit = vHits.length == 0 ? null : vHits[0].get(index);

            final NucleotideSequence targetSequence = target.targets[index].getSequence();

            if (vHit == null)
                return parameters.getAllowPartialAlignments() ? jAligner.align(targetSequence) : null;

            BitArray filterForJ = getFilter(GeneType.Joining, vHits);

            if (vHit.getAlignment().getSequence1Range().getTo() <=
                    vHit.getRecordPayload().getPartitioning().getRelativePosition(
                            parameters.getFeatureToAlign(GeneType.Variable),
                            ReferencePoint.FR3Begin)
                    || vHit.getAlignment().getSequence2Range().getTo() == targetSequence.size())
                return null;

            return jAligner.align(targetSequence,
                    vHit.getAlignment().getSequence2Range().getTo(),
                    targetSequence.size(), filterForJ);
        }

        /**
         * Filters hit to finally meet maxHit and minScore limits.
         */
        public void filterHits(float minTotalScore, int maxHits) {
            // Calculate this value once to use twice in the code below
            float totalMScore = minTotalScore - score();

            if (vHits != null && vHits.length > 0) {
                float minScore = Math.max(
                        parameters.getRelativeMinVScore() * vHits[0].sumScore,
                        totalMScore + vHits[0].sumScore // = minTotalScore - topJScore - topCScore - topDScore
                );
                this.vHits = extractHits(minScore, vHits, maxHits);
                assert vHits.length > 0;
            }

            if (jHits != null && jHits.length > 0) {
                this.jHits = extractHits(totalMScore + jHits[0].sumScore, // = minTotalScore - topVScore - topCScore - topDScore
                        jHits, maxHits);
                assert jHits.length > 0;
            }
        }

        /**
         * Filters hit to finally meet maxHit and minScore limits.
         */
        private PairedHit[] extractHits(float minScore, PairedHit[] result, int maxHits) {
            int count = 0;
            for (PairedHit hit : result)
                if (hit.sumScore >= minScore) {
                    if (++count >= maxHits)
                        break;
                } else
                    break;

            assert count > 0;

            return Arrays.copyOfRange(result, 0, count);
        }
    }

    public static Chains getPossibleDLoci(PairedHit[] vHits, PairedHit[] jHits) {
        Chains chains = new Chains();
        for (PairedHit h : vHits)
            chains = chains.merge(h.getGene().getChains());
        for (PairedHit h : jHits)
            chains = chains.merge(h.getGene().getChains());
        return chains;
    }

    /**
     * Converts array of "internal" PairedHits to a double array of KAlignmentHits to pass this value to a VDJAlignment
     * constructor (VDJAlignmentImmediate).
     */
    //@SuppressWarnings("unchecked")
    //static AlignmentHit<NucleotideSequence, Allele>[][] toArray(PairedHit[] hits) {
    //    AlignmentHit<NucleotideSequence, Allele>[][] hitsArray = new AlignmentHit[hits.length][];
    //    for (int i = 0; i < hits.length; ++i)
    //        hitsArray[i] = new AlignmentHit[]{hits[i].hit0, hits[i].hit1};
    //    return hitsArray;
    //}

    /**
     * Calculates normal "sum" score for each hit and sort hits according to this score.
     */
    static void calculateScoreAndSort(PairedHit[] hits) {
        for (PairedHit hit : hits)
            hit.calculateScore();
        Arrays.sort(hits, SCORE_COMPARATOR);
    }

    /**
     * Internal storage of paired hit. Combines information from two hits for right and left reads of a paired-end
     * read.
     */
    static final class PairedHit implements HasGene {
        AlignmentHit<NucleotideSequence, VDJCGene> hit0, hit1;
        float sumScore = -1, vEndScore = -1;

        PairedHit() {
        }

        PairedHit(AlignmentHit<NucleotideSequence, VDJCGene> hit0,
                  AlignmentHit<NucleotideSequence, VDJCGene> hit1,
                  boolean unsafe) {
            assert unsafe;
            this.hit0 = hit0;
            this.hit1 = hit1;
        }

        /**
         * Calculates alignment score only for FR3 and CDR3 part of V gene.
         */
        void calculateVEndScore(VDJCAlignerPVFirst aligner) {
            if (hit0 != null)
                vEndScore = aligner.calculateVEndScore(hit0);

            if (hit1 != null) {
                float sc = aligner.calculateVEndScore(hit1);
                if (vEndScore < sc)
                    vEndScore = sc;
            }
        }

        /**
         * Calculates normal "sum" score for this paired hit.
         */
        void calculateScore() {
            sumScore =
                    (hit0 == null ? 0.0f : hit0.getAlignment().getScore()) +
                            (hit1 == null ? 0.0f : hit1.getAlignment().getScore());
        }

        /**
         * To use this hit as an array of two single hits.
         */
        void set(int i, AlignmentHit<NucleotideSequence, VDJCGene> hit) {
            assert i == 0 || i == 1;
            if (i == 0)
                this.hit0 = hit;
            else
                this.hit1 = hit;
            assert hit0 == null || hit1 == null || hit0.getRecordPayload().getId().equals(hit1.getRecordPayload().getId());
        }

        /**
         * To use this hit as an array of two single hits.
         */
        AlignmentHit<NucleotideSequence, VDJCGene> get(int i) {
            assert i == 0 || i == 1;

            if (i == 0)
                return hit0;
            else
                return hit1;
        }

        /**
         * Returns id of reference sequence
         */
        @Override
        public VDJCGene getGene() {
            assert hit0 == null || hit1 == null || hit0.getRecordPayload() == hit1.getRecordPayload();
            return hit0 == null ? hit1.getRecordPayload() : hit0.getRecordPayload();
        }

        /**
         * Converts this object to a VDJCHit
         */
        @SuppressWarnings("unchecked")
        VDJCHit convert(GeneType geneType, VDJCAlignerPVFirst aligner) {
            Alignment<NucleotideSequence>[] alignments = new Alignment[2];

            VDJCGene gene = null;
            if (hit0 != null) {
                gene = hit0.getRecordPayload();
                alignments[0] = hit0.getAlignment();
            }
            if (hit1 != null) {
                assert gene == null ||
                        gene == hit1.getRecordPayload();
                gene = hit1.getRecordPayload();
                alignments[1] = hit1.getAlignment();
            }

            return new VDJCHit(gene, alignments,
                    aligner.getParameters().getFeatureToAlign(geneType));
        }
    }

    private static VDJCHit[] convert(PairedHit[] preHits,
                                     GeneType geneType, VDJCAlignerPVFirst aligner) {
        VDJCHit[] hits = new VDJCHit[preHits.length];
        for (int i = 0; i < preHits.length; i++)
            hits[i] = preHits[i].convert(geneType, aligner);
        return hits;
    }

    /**
     * Calculates alignment score only for FR3 and CDR3 part of V gene.
     */
    float calculateVEndScore(AlignmentHit<NucleotideSequence, VDJCGene> hit) {
        final VDJCGene gene = hit.getRecordPayload();
        final int boundary = gene.getPartitioning().getRelativePosition(
                parameters.getFeatureToAlign(GeneType.Variable),
                ReferencePoint.FR3Begin);
        final Alignment<NucleotideSequence> alignment = hit.getAlignment();

        if (alignment.getSequence1Range().getUpper() <= boundary)
            return 0.0f;

        if (alignment.getSequence1Range().getLower() >= boundary)
            return alignment.getScore();

        final Range range = new Range(boundary, alignment.getSequence1Range().getUpper());
        Mutations<NucleotideSequence> vEndMutations = alignment.getAbsoluteMutations().
                extractAbsoluteMutationsForRange(range);

        return AlignmentUtils.calculateScore(alignment.getSequence1(), range, vEndMutations,
                parameters.getVAlignerParameters().getParameters().getScoring());
    }

    static final Comparator<PairedHit> V_END_SCORE_COMPARATOR = new Comparator<PairedHit>() {
        @Override
        public int compare(PairedHit o1, PairedHit o2) {
            return Float.compare(o2.vEndScore, o1.vEndScore);
        }
    };

    static final Comparator<PairedHit> SCORE_COMPARATOR = new Comparator<PairedHit>() {
        @Override
        public int compare(PairedHit o1, PairedHit o2) {
            return Float.compare(o2.sumScore, o1.sumScore);
        }
    };

    @SuppressWarnings("unchecked")
    public static VDJCHit[] combine(final GeneFeature feature, final AlignmentHit<NucleotideSequence, VDJCGene>[][] hits) {
        for (int i = 0; i < hits.length; i++)
            Arrays.sort(hits[i], GENE_ID_COMPARATOR);
        ArrayList<VDJCHit> result = new ArrayList<>();

        // Sort-join-like algorithm
        int i;
        VDJCGene minGene;
        Alignment<NucleotideSequence>[] alignments;
        final int[] pointers = new int[hits.length];
        while (true) {
            minGene = null;
            for (i = 0; i < pointers.length; ++i)
                if (pointers[i] < hits[i].length && (minGene == null || minGene.getId().compareTo(
                        hits[i][pointers[i]].getRecordPayload().getId()) > 0))
                    minGene = hits[i][pointers[i]].getRecordPayload();

            // All pointers > hits.length
            if (minGene == null)
                break;

            // Collecting alignments for minAllele
            alignments = new Alignment[hits.length];
            for (i = 0; i < pointers.length; ++i)
                if (pointers[i] < hits[i].length && minGene == hits[i][pointers[i]].getRecordPayload()) {
                    alignments[i] = hits[i][pointers[i]].getAlignment();
                    ++pointers[i];
                }

            // Collecting results
            result.add(new VDJCHit(minGene, alignments, feature));
        }
        VDJCHit[] vdjcHits = result.toArray(new VDJCHit[result.size()]);
        Arrays.sort(vdjcHits);
        return vdjcHits;
    }

    public static final Comparator<AlignmentHit<NucleotideSequence, VDJCGene>> GENE_ID_COMPARATOR =
            new Comparator<AlignmentHit<NucleotideSequence, VDJCGene>>() {
                @Override
                public int compare(AlignmentHit<NucleotideSequence, VDJCGene> o1, AlignmentHit<NucleotideSequence, VDJCGene> o2) {
                    return o1.getRecordPayload().getId().compareTo(o2.getRecordPayload().getId());
                }
            };
}
