package cz.brmlab.yodaqa.pipeline;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.FSIndex;
import org.apache.uima.cas.FSIterator;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.fit.component.JCasAnnotator_ImplBase;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.brmlab.yodaqa.analysis.ansscore.AnswerFV;
import cz.brmlab.yodaqa.model.AnswerHitlist.Answer;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_OriginDBpOntology;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_OriginDBpProperty;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_OriginFreebaseOntology;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_OriginDocTitle;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_OriginMultiple;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_OriginPsgFirst;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_OriginPsgNE;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_OriginPsgNP;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_OriginPsgNPByLATSubj;
import cz.brmlab.yodaqa.model.CandidateAnswer.AnswerFeature;
import cz.brmlab.yodaqa.model.TyCor.LAT;
import cz.brmlab.yodaqa.model.TyCor.WordnetLAT;

/**
 * Merge textually similar answers in AnswerHitlistCAS.  Right now,
 * we just determine syntactic equivalence based on canonText
 * (dropping "the"-ish stuff, whitespaces and interpunction).
 *
 * (Do not confuse with AnswerCASMerger which just puts all CASes
 * of candidate answers together in a single CAS - the hitlist.)
 *
 * TODO: Produce more merging engines, e.g. LAT-specific mergers (esp. for
 * person names and dates) or "ignore everything up to the last comma" or
 * "drop parentheses" mergers.  Then, we would rename this to
 * AnswerSyntacticMerger and make AnswerTextMerger an aggregate AE. */

public class AnswerTextMerger extends JCasAnnotator_ImplBase {
	final Logger logger = LoggerFactory.getLogger(AnswerTextMerger.class);

	public void initialize(UimaContext aContext) throws ResourceInitializationException {
		super.initialize(aContext);
	}

	public void process(JCas jcas) throws AnalysisEngineProcessException {
		/* We determine a "core text" for each answer and merge
		 * answers wit the same core text. */
		Map<String, List<Answer>> answersByCoreText = new HashMap<String, List<Answer>>();

		FSIndex idx = jcas.getJFSIndexRepository().getIndex("SortedAnswers");
		FSIterator sortedAnswers = idx.iterator();
		while (sortedAnswers.hasNext()) {
			Answer a = (Answer) sortedAnswers.next();
			String coreText = a.getCanonText();
			// logger.debug("answer {} coreText {}", a.getText(), coreText);
			List<Answer> answers = answersByCoreText.get(coreText);
			if (answers == null) {
				answers = new LinkedList<Answer>();
				answersByCoreText.put(coreText, answers);
			}
			answers.add(a);
		}

		/* Now, we keep the top scored answer and merge in the FVs
		 * of the other variants. */
		for (Entry<String, List<Answer>> entry : answersByCoreText.entrySet()) {
			List<Answer> answers = entry.getValue();
			if (answers.size() == 1)
				continue;

			Answer bestAnswer = answers.remove(0);
			AnswerFV fv = new AnswerFV(bestAnswer);
			removeAnswer(bestAnswer);

			for (Answer oa : answers) {
				logger.debug("subsumed <<{}>>:{} < <<{}>>", oa.getText(), oa.getConfidence(), bestAnswer.getText());
				fv.merge(new AnswerFV(oa));
				// XXX: Merge LATs?  First let's just warn
				// if we are losing any.  Ugly hacky code.
				if (oa.getLats() != null) {
					for (FeatureStructure lat : oa.getLats().toArray()) {
						boolean alreadyHave = false;
						if (bestAnswer.getLats() != null) {
							for (FeatureStructure lat0 : bestAnswer.getLats().toArray()) {
								if (((LAT) lat0).getTypeIndexID() == ((LAT) lat).getTypeIndexID()
								    && ((LAT) lat0).getText().equals(((LAT) lat).getText())) {
									alreadyHave = true;
									break;
								}
							}
						}
						if (!alreadyHave && !(lat instanceof WordnetLAT))
							logger.warn(".. losing {} <<{}>>", ((LAT) lat).getClass().getSimpleName(), ((LAT) lat).getText());
						((LAT) lat).removeFromIndexes();
					}
				}
				removeAnswer(oa);
			}

			/* XXX: Code duplication with AnswerCASMerger;
			 * TODO: Move to a specialized annotator within
			 * AnswerScoringAE */
			/* At this point we can generate some features
			 * to be aggregated over all individual answer
			 * instances. */
			if (fv.getFeatureValue(AF_OriginPsgFirst.class)
			    + fv.getFeatureValue(AF_OriginPsgNP.class)
			    + fv.getFeatureValue(AF_OriginPsgNE.class)
			    + fv.getFeatureValue(AF_OriginPsgNPByLATSubj.class)
			    + fv.getFeatureValue(AF_OriginDocTitle.class)
			    + fv.getFeatureValue(AF_OriginDBpOntology.class)
			    + fv.getFeatureValue(AF_OriginDBpProperty.class)
			    + fv.getFeatureValue(AF_OriginFreebaseOntology.class) > 1.0)
				fv.setFeature(AF_OriginMultiple.class, 1.0);

			bestAnswer.setFeatures(fv.toFSArray(jcas));
			bestAnswer.addToIndexes();
		}
	}

	protected void removeAnswer(Answer a) {
		if (a.getFeatures() != null)
			for (FeatureStructure af : a.getFeatures().toArray())
				((AnswerFeature) af).removeFromIndexes();
		a.removeFromIndexes();
	}
}
