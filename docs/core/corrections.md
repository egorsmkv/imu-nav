# Cautious corrections without trusted GPS

[Rust core guide](README.md) · [Newcomer glossary](glossary.md)

Cell and network positions can help when GPS is unavailable, but they can be wrong by hundreds of
metres. Turns can also hint at progress along a route, but a phone rotation is not proof that the
vehicle turned. These inputs therefore make small, gated adjustments and leave uncertainty in place.
Cell-derived speed is a separate replay experiment and is off by default.

## `estimator/network_position.rs`: coarse position correction

The comparison estimator receives `PositioningHub.lastNet`, independently of the live engine's
position corrections. The Kotlin JNI wrapper excludes mock, GPS and fused fixes. Only car mode
uses this input; no new recording fields or sensor polling are needed.

- Fixes must be no more than 2.5 seconds old, with a positive reported accuracy at most 200 m.
  Accuracy is floored at 30 m. Future/out-of-order timestamps and the last eight repeated coordinates
  cannot contribute evidence. Projection work is limited to one distinct fix per five observation seconds.
- Three fixes spanning at least ten seconds must be reachable in sequence, with no gap over fifteen
  seconds and no abrupt change in residual relative to the prediction. An inconsistent fix restarts
  confirmation. A global projection rejects a coarse error corridor touching distant route occurrences,
  such as parallel returns, loops or crossings; fixes farther off-route than their accuracy are ignored.
- Fresh accepted GOOD GNSS position takes precedence. Corrections also require a discrepancy no larger
  than 500 m and a normalized squared innovation no larger than 9. Coherent but very distant cell
  locations do not force reacquisition.
- The coarse model uses twice the floored accuracy, plus a 150 km/h travel allowance for input age.
  It is applied at the tick time, not extrapolated with the DR speed. Each correction is at most
  25% of the innovation and 50 m. Position uncertainty cannot fall below that coarse model's standard
  deviation; speed is unchanged and accumulated systematic drift is retained. Position-speed
  correlation is cleared so later speed updates cannot reuse a coarse correction as precise evidence.
- Evidence and raw correction inputs are checkpointed for delayed-GNSS replay. At equal timestamps,
  OBD precedes GNSS calibration, then GNSS precedes coarse positions, motion hints and turn evidence. Rerouting
  discards route-specific confirmation while retaining duplicate protection.

These gates cannot eliminate persistent, plausible cell bias. They deliberately decline very coarse
coverage, ambiguous routes and recovery beyond 500 m rather than forcing a large position jump.
Reported uncertainty remains a model, not a navigation safety guarantee.

## `estimator/network_position/speed.rs`: opt-in cell-derived speed

`--native-network-speed` enables this **off-by-default** replay experiment. The app comparison path
and default replay retain position-only corrections. The same eligible coarse input is used, with no
new recording format or polling. `--no-native-network` takes precedence and disables both correction
channels (shared Kotlin cell-derived motion vetoes remain separate).

- Eligible fixes alternate between speed and position. At least four speed-reserved fixes spanning
  30–60 s form a batch; batches never overlap. A coherent slow-moving batch may extend to seven
  fixes (still at most 60 s) to meet the existing positive lower-bound gate without lowering it.
  All samples in that batch must participate in the fit. A speed-reserved fix never also updates position, even if the
  batch is incomplete or rejected. Position corrections consequently arrive less often.
- Weighted regression uses the raw projected positions, not corrected filter positions. Speed sigma
  is floored at 2 m/s and must be at most 4 m/s. The three-sigma lower bound must exceed 1 m/s, and
  speed must be at most 150 km/h. Adjacent segment speeds must agree within twice sigma (at least
  2 m/s), rejecting mixed stop/start or tower-jump windows. Input age adds 0.5 m/s per second to sigma.
- Speed updates pass an innovation gate of 9, have gain at most 0.5, and change speed by at most
  2 m/s. They leave position and systematic drift untouched, clear position-speed covariance and
  retain the speed-variance floor. They do not refresh GPS/OBD freshness or the OBD drift allowance.
- Fresh accepted GPS/OBD and stop/resume hints take priority. Accepted measured speed, accepted GOOD
  GPS position, intervening stop/ramp hints, rerouting, inconsistent fixes and gaps discard unfinished
  windows. Batch contents and allocation are checkpointed for delayed-GPS replay.
- Accepted cell learning saves the pre-learning speed prior. Two consecutive speed-reserved intervals
  can retract that learning before a full batch finishes: both interval speeds must disagree with the
  current speed in the same direction by more than `max(3 m/s, hypot(accuracy1, accuracy2) / dt)`, and
  both must be closer to the saved prior. The prior must exceed 1 m/s; a startup zero cannot invent a
  stop. This is a model-invalidation heuristic, not a confidence test or a new speed measurement.
  A single tower step, alternating errors, or a worse prior cannot trigger it.
- Recovery restores that prior, retains at least the previous speed variance and a 6 m/s sigma,
  and leaves position and accumulated drift unchanged. The triggering fix is speed-only; subsequent
  fixes get position-only corrections for 30 seconds. A fresh, disjoint batch is then required before
  learning resumes. Saved priors, departure confirmation and recovery timing are also checkpointed.
- Independently, two same-direction departures from the **last accepted regression speed** mark a
  manoeuvre even when the saved prior is worse. The threshold is the same coarse interval-error
  scale described above. Comparing against the fitted trend, rather than the lagging model speed,
  prevents stable new motion from repeatedly triggering recovery. Short differences alone never
  update speed. A complete coherent fit must still pass all moving/precision/innovation gates.
- While relearning, a coherent fit gets a speed-uncertainty floor of 6 m/s before fusion and a larger
  correction cap of 4 m/s; the gain remains capped at 0.5. After fusion the usual measurement-variance
  floor remains. Relearning ends once model speed is within the fitted sigma. An invalid/mixed window
  is discarded and subsequent fixes go to position for 15 seconds before a fresh speed batch starts.
  Accepted GPS/OBD, stop/ramp evidence and existing sequence resets clear this mode; delayed-GPS replay
  checkpoints both the trend reference and mode. Position and accumulated drift are never reset.

Disjoint fixes prevent direct sample reuse, not correlation between tower errors. Persistent coherent
tower drift can still look like speed. Saved-prior recovery only helps when that prior better explains
the contradictory movement. Relearning helps some other changes, but small changes and inaccurate or
sparse cells still lag. The 500 m discrepancy gate deliberately prevents forced reacquisition once
the model has diverged too far, so poor evidence can leave large errors unrecovered.
It is not an instantaneous speed sensor. Peak errors can exceed position-only correction, so the
experiment remains off by default pending broader real-drive validation.

## `estimator/turn.rs`: isolated turn matching

The shared pure-Kotlin `TurnDetector` uses recorded IMU samples and the existing gyro-bias estimate,
independently of the live engine's turn matching and snaps. It requires one second of low yaw before
rotation and 700 ms after it, a 1.5–8 second turn of 45–125 degrees, and at least 90% directional
consistency. Missing/non-finite inputs, gaps over 200 ms, yaw over 55 deg/s, excessive non-yaw rotation,
and acceleration above 6 m/s² invalidate evidence. Completed evidence expires two seconds after the
turn ends; repeated navigation ticks cannot refresh it. Trip and route changes reset this detector.

Rust indexes compact corners with straight approaches when immutable geometry is created. Broad
curves, U-turns and complex corner clusters are excluded. Car-only matching requires one candidate
within a 60–180 m prediction window and 15 degrees of the measured signed angle, with no other indexed
turn within 80 m. Fresh GOOD GPS, a stop/resume speed prior, speed outside 2–18 m/s, position sigma
above 300 m, or turn uncertainty above 100 m veto the correction. Events are consumed once; matching
has a 15-second cooldown and cannot immediately reuse the same landmark, even after that cooldown.

The turn midpoint is approximate: current speed advances its route position to the current tick.
Uncertainty includes a 30 m floor, half the modelled turn travel and speed uncertainty over that age.
The existing coarse-position update limits correction to 25% of the innovation and 30 m, keeps a
measurement-sized uncertainty floor, and changes neither speed nor accumulated systematic drift.
Turn evidence and deduplication state participate in delayed-GNSS replay, including equal timestamps;
reroutes reject rotations begun on the old route.

A slow, smooth phone yaw while driving can still resemble a vehicle turn. Likewise, a wrong road
branch with a similar angle can match planned geometry: this is not independent intersection or
off-route recognition. These unresolved ambiguities are why corrections remain weak and the native
estimator remains comparison-only in this research prototype.
