# eval/ — detector evaluation and protocol vectors (owner: stream 1, Chloe)

```
gen_vectors.py   FOF-1 reference implementation (stdlib only); writes vectors/*.json and firmware/selftest/vectors.h
vectors/         one JSON per case: friend, friend_south, friend_midnight_utc, replayed_nonce, bad_tag,
                 position_mismatch, late_response, no_response (deterministic: fixed TEST master key and timestamps)
images/          TODO: the test set (not committed yet)
ground_truth.json  TODO
run_eval.py      TODO: precision/recall per detector against the served model
```

Regenerate the vectors after any protocol change (and commit both outputs): `python eval/gen_vectors.py`.

## Test set plan (H10–14)

- 10+ images, 1280x720, taken with the demo phone in the venue lighting: the demo drone target (whatever RT-DETR
  boxes reliably — decided at H4–7) at 2 m, 4 m and 8 m, with and without a person in frame, plus 2 negatives
  (empty wall, a person only).
- `ground_truth.json`: `{"<file>": [{"cls": "drone"|"person", "box": [xmin, ymin, xmax, ymax]}, ...]}` in pixels.
  Label with any box tool, or by hand from the serve dashboard's boxes.
- Never commit anything over a few MB; keep images at JPEG quality 85.

## run_eval.py plan

1. For each image, `POST /v1/detections` to `clikart-cli serve --offline` (see laptop/README.md), with
   `confidence` 0.05 so the threshold sweep happens on the laptop.
2. Map `label_id` through the same table as the app (`0 → person`, `4/14/33 → drone`); everything else is ignored.
3. For each class and each threshold in `0.1 … 0.9`: match predictions to ground truth at IoU ≥ 0.5, count
   TP/FP/FN, print precision/recall/F1; recommend the per-class thresholds (`LabelMap` defaults are 0.5 / 0.28).
4. Optional: the same for OWLv2 (`google/owlv2-base-patch16-ensemble`, prompt `drone,quadcopter,person`) to decide
   whether the accuracy option is worth its latency — bench on the phone first.
5. Output a Markdown table for README section 8 (numbers: load time, ms/frame on the phone, precision/recall).
