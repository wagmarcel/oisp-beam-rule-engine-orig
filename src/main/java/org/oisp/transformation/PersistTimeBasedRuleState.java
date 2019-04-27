package org.oisp.transformation;

import com.sun.jdi.IntegerValue;
import org.apache.beam.sdk.coders.MapCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.state.MapState;
import org.apache.beam.sdk.state.StateSpec;
import org.apache.beam.sdk.state.StateSpecs;
import org.apache.beam.sdk.state.ValueState;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.util.VarInt;
import org.apache.beam.sdk.values.KV;
import org.oisp.collection.Rule;
import org.oisp.collection.RuleCondition;
import org.oisp.collection.RuleWithRuleConditions;
import org.oisp.transformation.storage.RuleComponentsStorage;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class PersistTimeBasedRuleState extends DoFn<KV<String,RuleWithRuleConditions>, KV<String, RuleWithRuleConditions>> {
    @DoFn.StateId("ruleCondHash") //contains the RC with timebased state (i.e. hash with timestamp and fulfillment
    private final StateSpec<ValueState<Map<Integer, RuleCondition>>> state =
            StateSpecs.value(MapCoder.<Integer, RuleCondition>of(VarIntCoder.of(), SerializableCoder.of(RuleCondition.class)));

    @ProcessElement
    public void processElement(ProcessContext c,
                               @StateId("ruleCondHash") ValueState<Map<Integer, RuleCondition>> condSamples) {

        //Record all ruleconditions per Rule
        RuleWithRuleConditions rwRC = c.element().getValue();
        Rule rule = rwRC.getRule();
        Map<Integer, RuleCondition> state = condSamples.read();
        if (state == null) {
            condSamples.write(new TreeMap<Integer, RuleCondition>());
            state = condSamples.read();
        }
        SortedMap<Integer, RuleCondition> rch = rwRC.getRcHash();
        if (rch == null) {
            return;
        }
        for (SortedMap.Entry<Integer, RuleCondition> entry : rch.entrySet()) {
            //get state RC lists and merge it
            if (state.get(entry.getKey()) == null) {
                state.put(entry.getKey(), entry.getValue().clone(entry.getValue()));
            } else {
                state.get(entry.getKey()).getTimeBasedState().putAll(entry.getValue().getTimeBasedState());
            }
            //clean up samples
            Boolean result = checkFulfillmentAndCleanup(state.get(entry.getKey()));
            state.get(entry.getKey()).setFulfilled(result);
        }
        condSamples.write(state);
        RuleWithRuleConditions mutableRWRC = new RuleWithRuleConditions(rule);
        SortedMap<Integer, RuleCondition> mutableSM = new TreeMap<Integer, RuleCondition>(state);
        mutableRWRC.setRcHash(mutableSM);
        c.output(KV.of(rule.getId(), mutableRWRC));
    }

    //checks fulfillment and removes all not needed part in the content
    private Boolean checkFulfillment(RuleCondition rc, SortedMap<Long, Boolean> timeBasedSubtree) {

        //Can we delete the subtree? Only when subtree is sufficient long in the past
        // (to avoid missing an interval because of reordering)
        //what is sufficient? 60s?
        Boolean removeSubTree = false;
        Long latestTS = rc.getTimeBasedState().lastKey();
        Long latestInSubTree = timeBasedSubtree.lastKey();
        if (latestTS - latestInSubTree > 60) {
            removeSubTree = true;
        }
        if (timeBasedSubtree.size() < 3) {
            if (removeSubTree) {
                timeBasedSubtree.clear();
            }
            return false;
        }
        Integer numFulfilled = 0;
        Long firstTS = -1L, lastTS = -1L;
        for (Map.Entry<Long, Boolean> entry : timeBasedSubtree.entrySet()) {
            if (entry.getValue()) {
                if (numFulfilled == 0) {
                    firstTS = entry.getKey();
                }
                numFulfilled++;
                lastTS = entry.getKey();
            }
        }
        if (lastTS - firstTS >= rc.getTimeLimit()) {
            if (removeSubTree) {
                timeBasedSubtree.clear();
            }
            return true;
        }
        return false;
    }


    //removes obvioiusly not needed part of the hash
    //E.g. all fulfilled element at the beginning, all unfulfilled elements which are too close to other unfulfilled
    //Checks and removes all non fulfilled intervals. Keeps the fulfilled.
    private Boolean checkFulfillmentAndCleanup(RuleCondition rc) {
        SortedMap<Long, Boolean> timeBasedState = rc.getTimeBasedState();
        if (timeBasedState.size() <= 1) {
            return false;
        }
        //Iterate through the samples and remove all intervals which are < timelimit between unfulfilled values
        //Exception: first sample can be fulfilled
        Long firstTS = timeBasedState.firstKey();
        Long nextTS = firstTS;
        Boolean done = false;
        while(!done) {
            for (Map.Entry<Long, Boolean> entry : timeBasedState.tailMap(firstTS).entrySet()) {
                Boolean fulFillmentValue = entry.getValue();
                if (!fulFillmentValue) {
                    nextTS = entry.getKey();
                }
            }
            //at this point we either have a nextTS!=firstTS which is unfulfilled or nextTS==firstTS
            if (nextTS != firstTS) {

                if (nextTS - firstTS < rc.getTimeLimit()) {
                    //Max possible limit is smaller than timeLimit so discard the content
                    //Except the nextTS which will become the firstTS
                    timeBasedState.headMap(nextTS - 1).clear();
                } else {
                    Boolean result = checkFulfillment(rc, timeBasedState.subMap(firstTS, nextTS));
                }
                firstTS = nextTS;
            } else {
                done = true;
            }
        }
        return checkFulfillment(rc, timeBasedState.tailMap(firstTS));
    }
}