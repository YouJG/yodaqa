package cz.brmlab.yodaqa.pipeline;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.AbstractCas;
import org.apache.uima.cas.FSIndex;
import org.apache.uima.cas.FSIterator;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.fit.component.JCasMultiplier_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.CasCopier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.brmlab.yodaqa.analysis.ansscore.AnswerFV;
import cz.brmlab.yodaqa.model.AnswerHitlist.Answer;
import cz.brmlab.yodaqa.model.CandidateAnswer.AnswerInfo;
import cz.brmlab.yodaqa.model.Question.Focus;
import cz.brmlab.yodaqa.model.Question.QuestionInfo;
import cz.brmlab.yodaqa.model.TyCor.LAT;

/**
 * Take an input AnswerHitlistCAS and generate per-answer CandidateAnswerCAS
 * instances.
 *
 * We are a simple CAS multiplier that creates a dedicated CAS for
 * each to-be-reanalyzed candidate answer.  However, first we return
 * AnswerHitlistCAS again to keep it flowing. */

public class AnswerCASSplitter extends JCasMultiplier_ImplBase {
	final static Logger logger = LoggerFactory.getLogger(AnswerCASSplitter.class);

	/**
	 * Number of top answers to extract.
	 */
	public static final String PARAM_TOPLISTLEN = "TOPLISTLEN";
	@ConfigurationParameter(name = PARAM_TOPLISTLEN, mandatory = false, defaultValue = "5")
	protected int topListLen;

	/**
	 * Whether to emit the original hitlist as the first output CAS
	 * to pass it through.
	 */
	public static final String PARAM_HITLIST_EMIT = "HITLIST_EMIT";
	@ConfigurationParameter(name = PARAM_HITLIST_EMIT, mandatory = false, defaultValue = "true")
	protected boolean hitlistEmit;

	JCas baseJcas;
	QuestionInfo qi;

	/* Prepared list of answers to return. */
	FSIterator answers;
	int i;

	public void initialize(UimaContext aContext) throws ResourceInitializationException {
		super.initialize(aContext);
	}

	public void process(JCas jcas) throws AnalysisEngineProcessException {
		baseJcas = jcas;
		JCas questionView, hitlistView;
		try {
			questionView = jcas.getView("Question");
			hitlistView = jcas.getView("AnswerHitlist");
		} catch (Exception e) {
			throw new AnalysisEngineProcessException(e);
		}

		qi = JCasUtil.selectSingle(questionView, QuestionInfo.class);

		FSIndex idx = hitlistView.getJFSIndexRepository().getIndex("SortedAnswers");
		answers = idx.iterator();
		i = hitlistEmit ? 0 : 1;
	}

	public boolean hasNext() throws AnalysisEngineProcessException {
		return (i - 1 < topListLen && answers.hasNext()) || i <= 1;
	}

	public AbstractCas next() throws AnalysisEngineProcessException {
		if (i == 0) {
			/* First, return the original hitlist. */
			i++;
			JCas jcas = getEmptyJCas();
			CasCopier.copyCas(baseJcas.getCas(), jcas.getCas(), true);
			return jcas;
		}

		i++;
		Answer answer = null;
		if (answers.hasNext())
			answer = (Answer) answers.next();

		JCas jcas = getEmptyJCas();
		try {
			JCas questionView, hitlistView;
			questionView = baseJcas.getView("Question");
			hitlistView = baseJcas.getView("AnswerHitlist");

			JCas canQuestionView = jcas.createView("Question");
			copyQuestion(questionView, canQuestionView);

			JCas canAnswerView = jcas.createView("Answer");
			if (answer != null) {
				// logger.debug("out: answer {}", answer.getText());
				generateAnswer(answer, canAnswerView, !hasNext());
			} else {
				/* We will just generate a single dummy CAS
				 * to avoid flow breakage. */
				canAnswerView.setDocumentText("");
				canAnswerView.setDocumentLanguage("en"); // XXX
				AnswerInfo ai = new AnswerInfo(canAnswerView);
				ai.setIsLast(true);
				ai.addToIndexes();
			}

		} catch (Exception e) {
			jcas.release();
			throw new AnalysisEngineProcessException(e);
		}
		return jcas;
	}

	protected void copyQuestion(JCas src, JCas dest) throws Exception {
		CasCopier copier = new CasCopier(src.getCas(), dest.getCas());
		copier.copyCasView(src.getCas(), dest.getCas(), true);
	}

	protected void generateAnswer(Answer answer, JCas jcas,
			boolean isLast) throws Exception {
		jcas.setDocumentText(answer.getText());
		jcas.setDocumentLanguage(answer.getCAS().getDocumentLanguage());

		/* Grab answer features */
		AnswerFV srcFV = new AnswerFV(answer);

		/* Generate the AnswerInfo singleton */
		AnswerInfo ai = new AnswerInfo(jcas);
		ai.setCanonText(answer.getCanonText());
		ai.setFeatures(srcFV.toFSArray(jcas));
		ai.setIsLast(isLast);
		ai.addToIndexes();

		/* Generate the Focus */
		if (answer.getFocus() != null) {
			Focus f = new Focus(jcas);
			f.setBegin(answer.getText().indexOf(answer.getFocus()));
			f.setEnd(f.getBegin() + answer.getFocus().length());
			f.addToIndexes();
		}
		/* Generate the LATs */
		CasCopier copier = new CasCopier(answer.getCAS(), jcas.getCas());
		for (FeatureStructure lat : answer.getLats().toArray()) {
			LAT lat2 = (LAT) copier.copyFs(lat);
			lat2.addToIndexes();
		}
	}
}
