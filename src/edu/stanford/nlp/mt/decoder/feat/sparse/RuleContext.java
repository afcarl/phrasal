package edu.stanford.nlp.mt.decoder.feat.sparse;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import edu.stanford.nlp.mt.base.ConcreteRule;
import edu.stanford.nlp.mt.base.CoverageSet;
import edu.stanford.nlp.mt.base.FeatureValue;
import edu.stanford.nlp.mt.base.Featurizable;
import edu.stanford.nlp.mt.base.IString;
import edu.stanford.nlp.mt.base.Sequence;
import edu.stanford.nlp.mt.base.Sequences;
import edu.stanford.nlp.mt.base.SimpleSequence;
import edu.stanford.nlp.mt.base.SourceClassMap;
import edu.stanford.nlp.mt.base.TargetClassMap;
import edu.stanford.nlp.mt.base.TokenUtils;
import edu.stanford.nlp.mt.decoder.feat.DerivationFeaturizer;
import edu.stanford.nlp.mt.decoder.feat.FeaturizerState;
import edu.stanford.nlp.mt.decoder.feat.NeedsCloneable;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.PropertiesUtils;

/**
 * Rule identity with appended source, class-based translation history.
 * 
 * @author Spence Green
 *
 */
public class RuleContext extends DerivationFeaturizer<IString, String> implements NeedsCloneable<IString,String>{

  private static final String FEATURE_NAME = "RCTX";
  private static final int DEFAULT_LEXICAL_CUTOFF = 50;
  private static final int CONTEXT_LENGTH = 2;

  private static final Sequence<IString> START_SEQUENCE;
  static {
    IString[] token = {TokenUtils.START_TOKEN};
    START_SEQUENCE = new SimpleSequence<IString>(true, token);
  }

  private final boolean addLexicalizedRule;
  private final boolean addClassBasedRule;
  private final int countFeatureIndex;
  private final SourceClassMap sourceMap;
  private final TargetClassMap targetMap;
  private final boolean addDomainFeatures;
  private final Map<Integer, Pair<String, Integer>> sourceIdInfoMap;
  private final int lexicalCutoff;

  private Sequence<IString> sourceClassSequence;

  /**
   * Constructor.
   * 
   */
  public RuleContext() {
    this.addLexicalizedRule = true;
    this.addClassBasedRule = false;
    this.countFeatureIndex = -1;
    this.sourceMap = SourceClassMap.getInstance();
    this.targetMap = null;
    this.addDomainFeatures = false;
    this.sourceIdInfoMap = null;
    this.lexicalCutoff = DEFAULT_LEXICAL_CUTOFF;
  }

  /**
   * Constructor.
   * 
   * @param args
   */
  public RuleContext(String... args) {
    Properties options = SparseFeatureUtils.argsToProperties(args);
    this.addLexicalizedRule = options.containsKey("addLexicalized");
    this.addClassBasedRule = options.contains("addClassBased");
    this.countFeatureIndex = PropertiesUtils.getInt(options, "countFeatureIndex", -1);
    sourceMap = SourceClassMap.getInstance();
    targetMap = addClassBasedRule ? TargetClassMap.getInstance() : null;
    this.addDomainFeatures = options.containsKey("domainFile");
    sourceIdInfoMap = addDomainFeatures ? SparseFeatureUtils.loadGenreFile(options.getProperty("domainFile")) : null;
    this.lexicalCutoff = PropertiesUtils.getInt(options, "lexicalCutoff", DEFAULT_LEXICAL_CUTOFF);
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    return super.clone();
  }

  @Override
  public void initialize(int sourceInputId,
      List<ConcreteRule<IString, String>> ruleList, Sequence<IString> source) {
    IString[] sourceToClass = new IString[source.size()];
    for (int i = 0, limit = source.size(); i < limit; ++i) {
      sourceToClass[i] = sourceMap.get(source.get(i));
    }
    sourceClassSequence = new SimpleSequence<IString>(true, sourceToClass);
  }

  @Override
  public List<FeatureValue<String>> featurize(Featurizable<IString, String> f) {
    List<FeatureValue<String>> features = Generics.newLinkedList();
    Pair<String,Integer> genreInfo = addDomainFeatures && sourceIdInfoMap.containsKey(f.sourceInputId) ? 
        sourceIdInfoMap.get(f.sourceInputId) : null;
        final String genre = genreInfo == null ? null : genreInfo.first();

        // Retrieve context
        RuleContextState priorState = f.prior == null ? null : (RuleContextState) f.prior.getState(this);
        Sequence<IString> context = priorState == null ? START_SEQUENCE : priorState.state;
        String contextStr = context.toString("-");

        if (addLexicalizedRule && aboveThreshold(f.rule)) {
          String sourcePhrase = f.sourcePhrase.toString("-");
          String targetPhrase = f.targetPhrase.toString("-");
          String featureString = FEATURE_NAME + ":" + String.format("%s|%s>%s", contextStr, sourcePhrase, targetPhrase);
          features.add(new FeatureValue<String>(featureString, 1.0));
          if (genre != null) {
            features.add(new FeatureValue<String>(featureString + "-" + genre, 1.0));
          }
        }
        if (addClassBasedRule) {
          StringBuilder sb = new StringBuilder();
          for (IString token : f.sourcePhrase) {
            if (sb.length() > 0) sb.append("-");
            String tokenClass = sourceMap.get(token).toString();
            sb.append(tokenClass);
          }
          sb.append(">");
          boolean seenFirst = false;
          for (IString token : f.targetPhrase) {
            if (seenFirst) sb.append("-");
            String tokenClass = targetMap.get(token).toString();
            sb.append(tokenClass);
            seenFirst = true;
          }
          String featureString = String.format("%s:%s|%s",FEATURE_NAME, contextStr, sb.toString());
          features.add(new FeatureValue<String>(featureString, 1.0));
          if (genre != null) {
            features.add(new FeatureValue<String>(featureString + "-" + genre, 1.0));
          }
        }

        f.setState(this, new RuleContextState(makeContext(f)));

        return features;
  }

  private Sequence<IString> makeContext(Featurizable<IString, String> f) {
    int srcSize = f.sourcePhrase.size();
    if (srcSize >= CONTEXT_LENGTH) {
      return sourceClassSequence.subsequence(srcSize-CONTEXT_LENGTH, srcSize);

    } else {
      CoverageSet coverage = new CoverageSet();
      for (; f != null; f = f.prior) {
        for (int i = 0, limit = f.sourcePhrase.size(); i < limit; ++i) {
          coverage.set(f.sourcePosition+i);
          if (coverage.cardinality() == CONTEXT_LENGTH) {
            return sourceClassSequence.subsequence(coverage);
          }
        }
      }
      return Sequences.concatenate(START_SEQUENCE, sourceClassSequence.subsequence(coverage));
    }
  }

  private boolean aboveThreshold(ConcreteRule<IString, String> rule) {
    if (countFeatureIndex < 0) return true;
    if (countFeatureIndex >= rule.abstractRule.scores.length) {
      // Generated by unknown word model...don't know count.
      return false;
    }
    int count = (int) Math.round(Math.exp(rule.abstractRule.scores[countFeatureIndex]));
    return count > lexicalCutoff;
  }


  private static class RuleContextState extends FeaturizerState {

    private final Sequence<IString> state;

    public RuleContextState(Sequence<IString> state) {
      this.state = state;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      } else if ( ! (other instanceof RuleContextState)) {
        return false;
      } else {
        RuleContextState o = (RuleContextState) other;
        return this.state.equals(o.state);
      }
    }

    @Override
    public int hashCode() {
      return state.hashCode();
    }
  }
}
