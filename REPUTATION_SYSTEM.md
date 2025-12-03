# Reputation System

## Overview

The Sentinel reputation system is a sophisticated voting mechanism that allows players to build community-driven reputations. The system uses a complex formula that considers multiple factors to prevent abuse, reward quality feedback, and ensure fair scoring.

**Key Features:**
- Anonymous voting with comment support
- Time-based vote decay (older votes count less)
- Anti-abuse detection (brigading, reciprocal voting, spam)
- Voter credibility scoring (new accounts, spam, consensus agreement)
- Comment quality weighting
- Small server scaling bonuses
- Hybrid caching for performance

**Access:**
- Discord: `/rep view <username>` or `/rep vote <username> +/- [comment]`
- In-Game: `/rep <username>` or `/rep <username> +/- [comment]`

---

## Core Formula

The total reputation score is calculated as:

```
Total Reputation = Σ(vote_value × vote_weight × time_decay)

Where vote_weight = base_credibility × all_modifiers
```

The final **display score** shown to players is normalized to a -100 to +100 range using:

```
Display Score = tanh(Total Reputation / 10) × 100
```

This normalization ensures that:
- Scores remain human-readable
- Extreme scores are compressed (diminishing returns)
- The scale is consistent regardless of total votes

---

## Time Decay

**Purpose:** Older votes gradually lose influence, ensuring reputation reflects recent behavior.

**Formula:**
```
time_decay = e^(-0.023 × days_old)
```

**Effect:**
- After 30 days: Vote worth ~50% of original weight
- After 60 days: Vote worth ~25% of original weight
- After 90 days: Vote worth ~12.5% of original weight

**Configuration:**
- `timeDecayRate`: Default 0.023 (adjustable in config)

**Why this matters:** A player's reputation gradually reflects their current behavior rather than being permanently affected by old votes.

---

## Voter Credibility

Not all votes are equal. The system calculates a credibility score for each voter based on multiple factors:

### 1. Account Age

**Formula:**
```
account_age_factor = min(1.0, account_days / 30)
```

**Effect:**
- Day 1: 3.3% credibility
- Day 15: 50% credibility
- Day 30+: 100% credibility

**Why:** Prevents new accounts from immediately manipulating scores. Encourages long-term community participation.

### 2. Vote Diversity

**Applies to:** Voters with 5+ total votes

**Formula:**
```
If 95%+ of votes are same direction (all positive OR all negative):
  diversity_penalty = 1.0 - (skewness - 0.95) × 6.0
  (minimum: 0.7× weight)
```

**Effect:**
- 50/50 split: No penalty
- 80/20 split: No penalty
- 95/5 split: Slight penalty begins
- 100/0 split: 0.7× weight (30% reduction)

**Why:** Prevents users from only upvoting friends or only downvoting enemies. Encourages balanced, fair voting.

### 3. Spam Dampener

**Formula:**
```
spam_dampener = 1.0 / (1 + votes_last_24h × 0.1)
```

**Effect:**
- 0 votes in 24h: 1.0× (no penalty)
- 5 votes in 24h: 0.67× weight
- 10 votes in 24h: 0.5× weight
- 20 votes in 24h: 0.33× weight

**Why:** Prevents vote spam. Encourages thoughtful, occasional voting rather than mass voting sessions.

### 4. Voter's Own Reputation

**Formula:**
```
If voter_score >= 50:
  rep_multiplier = 1.0 + ((voter_score - 50) / 100) × 0.5
  (range: 1.0× to 1.5×)

If voter_score <= -50:
  rep_multiplier = 1.0 - ((|voter_score| - 50) / 100) × 0.5
  (range: 1.0× to 0.5×)

Otherwise (between -50 and +50):
  rep_multiplier = 1.0×
```

**Effect:**
- High reputation voters (75+): Their votes count more (up to 1.5×)
- Low reputation voters (-75 or below): Their votes count less (down to 0.5×)
- Neutral voters: Normal weight

**Why:** Trusted community members' opinions carry more weight. Known troublemakers have reduced influence.

### 5. Consensus Agreement

**Requires:** 10+ consensus checks

**Formula:**
```
Agreement Rate = agreements / (agreements + disagreements)

70-100%: 1.0× weight
50-70%:  0.9× weight
30-50%:  0.7× weight
0-30%:   0.5× weight
```

**How it works:**
- After 30 days, the system checks if a vote aligns with the eventual consensus
- If most people upvoted someone and you did too: Agreement++
- If most people downvoted someone and you upvoted: Disagreement++

**Why:** Rewards voters who make accurate assessments. Over time, consistently good judges gain more influence.

---

## Anti-Abuse Detection

### 1. Reciprocal Voting

**Detects:** When two players vote on each other with the same sign (+/+ or -/-)

**Penalties:**
```
Within 1 hour:    0.4× weight (60% reduction)
Within 1-7 days:  0.75× weight (25% reduction)
After 7 days:     No penalty
```

**Why:** Prevents coordinated vote trading ("you upvote me, I'll upvote you") or revenge downvoting.

### 2. Vote Brigading

**Detects:** 3+ same-sign votes on a target within 10 minutes

**Penalty:** All votes in that cluster get 0.3× weight (70% reduction)

**Why:** Prevents coordinated mass voting campaigns, whether organized harassment or artificial boosting.

### 3. Vote Cooldown

**Rule:** 7 days between votes on the same target (configurable)

**Effect:** Cannot vote again until cooldown expires

**Why:** Prevents spam voting the same person repeatedly. Encourages voting on diverse players.

### 4. Self-Voting Prevention

**Rule:** Cannot vote on yourself

**Effect:** Vote is rejected immediately

**Why:** Obviously prevents self-promotion.

---

## Comment Quality

Comments affect vote weight:

### No Comment
**Weight:** 0.9× (10% reduction)

**Why:** Encourages providing context. Anonymous votes with no explanation are slightly devalued.

### Short Comment (10-50 characters)
**Weight:** 1.0× (normal)

**Example:** "Great player!" or "Was helpful"

### Detailed Comment (50+ characters)
**Weight:** 1.3× (30% bonus)

**Example:** "Helped me build my base and gave me resources when I was starting out. Very friendly and patient."

**Why:** Rewards thoughtful, detailed feedback that helps the community understand the vote.

### Vague Comment
**Weight:** 0.7× (30% reduction)

**Triggers:** Contains words like "trash", "noob", "bad", "sucks", "terrible", "awful", "worst"

**Why:** Discourages low-quality negative feedback. Forces people to articulate specific concerns.

---

## Diversity Penalty

**Purpose:** Reduce weight of votes when few unique voters are involved.

**Formula:**
```
positive_diversity = unique_positive_voters / total_positive_votes
negative_diversity = unique_negative_voters / total_negative_votes

If positive_diversity < negative_diversity:
  positive_weight *= (positive_diversity / negative_diversity)^0.5

If negative_diversity < positive_diversity:
  negative_weight *= (negative_diversity / positive_diversity)^0.5
```

**Example:**
- 10 positive votes from 3 people (0.3 diversity)
- 5 negative votes from 5 people (1.0 diversity)
- Positive votes get: (0.3/1.0)^0.5 = 0.55× weight reduction
- Negative votes stay at full weight

**Why:** Prevents a small group of friends from artificially inflating someone's score. Real community consensus requires diverse voters.

---

## Progressive Reputation Adjustments

**Purpose:** Make it harder to harm popular players and easier to help unpopular ones.

### Downvoting Popular Players (80th+ Percentile)

**Formula:**
```
progress = (percentile - 80) / (100 - 80)
penalty = 1.0 - progress × 0.5

(Scales from 1.0× at 80th percentile to 0.5× at 100th)
```

**Effect:**
- Downvoting someone at 80th percentile: Full weight
- Downvoting someone at 90th percentile: 0.75× weight
- Downvoting someone at 100th percentile: 0.5× weight

**Why:** Prevents pile-on harassment of well-liked community members. Requires strong justification to bring down top players.

### Upvoting Unpopular Players (0-20th Percentile)

**Formula:**
```
progress = (20 - percentile) / 20
bonus = 1.0 + progress × 0.5

(Scales from 1.0× at 20th percentile to 1.5× at 0th)
```

**Effect:**
- Upvoting someone at 20th percentile: Full weight
- Upvoting someone at 10th percentile: 1.25× weight
- Upvoting someone at 0th percentile: 1.5× weight

**Why:** Encourages redemption. Helps players recover from low reputation when they improve behavior.

---

## Small Server Scaling

**Purpose:** Boost reputation scores on small servers where fewer total votes are expected.

**Multipliers:**

| Unique Voters | Multiplier |
|---------------|------------|
| < 3           | 1.0× (no bonus) |
| 3-5           | 1.1× |
| 6-10          | 1.2× |
| 11-15         | 1.3× |
| 16-20         | 1.4× |
| 20+           | 1.5× (maximum) |

**Effect:** A player with 10 votes on a small server gets similar visible reputation as someone with more votes on a large server.

**Why:** Makes the system viable for small communities. Prevents scores from appearing too small when total player count is low.

---

## Caching System

**Performance Optimization:** Calculating reputation scores in real-time for every view would be expensive.

### Hybrid Caching Strategy

**Cache Fresh (< 60 minutes):**
- Return cached score immediately
- No recalculation needed

**Cache Stale (> 60 minutes):**
- Recalculate score with fresh data
- Update cache
- Return new score

**Benefits:**
- Fast response times for frequently viewed players
- Accurate scores that reflect recent votes
- Reduced database load

### Scheduled Tasks

**Cache Refresh (Every 60 minutes):**
- Scans all players with stale caches
- Recalculates if new votes exist
- Updates percentile ranks

**Consensus Tracking (Daily):**
- Checks votes older than 30 days
- Compares voter's votes with current consensus
- Updates voter credibility scores

**Percentile Updates (Every 60 minutes):**
- Recalculates all players' percentile ranks
- Ensures progressive penalties/bonuses use fresh data

---

## Display Format

### Discord Embed

```
Reputation for PlayerName
Score: 75 | Total Votes: 15 | Percentile: 85.2%

Recent Votes:
**+** Jan 15, 2025 - Great player, very helpful!
+ Jan 14, 2025 - [no comment]
**+** Jan 13, 2025 - Really friendly
- Jan 12, 2025 - [no comment]
**+** Jan 11, 2025 - Good teammate
```

**Features:**
- Color-coded embed (red/gray/green based on score)
- Bold symbols for votes with comments
- Anonymous (no voter names shown)
- Most recent votes first

### In-Game Display

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━
Reputation for PlayerName
Score: 75 | Votes: 15 | Percentile: 85.2%
Recent: + + + - +
         ↑ hover for tooltip
━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Tooltip (on hover):**
```
Jan 15, 2025
Great player, very helpful!
```

**Features:**
- Color-coded symbols (green/red)
- Bold symbols for votes with comments
- Tooltips show date and comment
- Compact single-line visualization

---

## Configuration

All weights and factors are configurable in `config.json`:

```json
{
  "reputation": {
    "timeDecayRate": 0.023,
    "voteCooldownDays": 7,
    "fullCredibilityDays": 30,
    "spamDampenerFactor": 0.1,
    "highRepMultiplierMax": 1.5,
    "lowRepMultiplierMin": 0.5,
    "reciprocalQuickWeight": 0.4,
    "reciprocalDelayedWeight": 0.75,
    "brigadingWeight": 0.3,
    "singleDirectionWeight": 0.7,
    "noCommentWeight": 0.9,
    "shortCommentWeight": 1.0,
    "detailedCommentWeight": 1.3,
    "vagueCommentWeight": 0.7,
    "vagueCommentPatterns": ["trash", "noob", "bad", "sucks"],
    "highPercentileThreshold": 80.0,
    "lowPercentileThreshold": 20.0,
    "highPercentileMinWeight": 0.5,
    "lowPercentileMaxWeight": 1.5,
    "consensusDays": 30,
    "cacheStaleMinutes": 60,
    "scheduleRefreshMinutes": 60,
    "displayRecentVotesCount": 10
  }
}
```

**Tuning Tips:**
- **Stricter anti-abuse:** Lower `reciprocalQuickWeight`, `brigadingWeight`
- **Faster decay:** Increase `timeDecayRate`
- **Slower decay:** Decrease `timeDecayRate`
- **Reward comments more:** Increase `detailedCommentWeight`
- **Faster new account credibility:** Decrease `fullCredibilityDays`

---

## Example Calculations

### Example 1: New Player Vote

**Scenario:**
- New player (5 days old) upvotes someone
- No comment provided
- Voted on 2 other people in last 24h

**Calculation:**
```
Base vote value: +1

Account age factor: min(1.0, 5/30) = 0.167
Spam dampener: 1.0 / (1 + 2×0.1) = 0.833
Comment quality: 0.9 (no comment)
Time decay: 1.0 (just voted)

Final weight: 1 × 0.167 × 0.833 × 0.9 × 1.0 = 0.125

Contribution to score: +0.125
```

**Interpretation:** New account's uncommented vote is worth about 12.5% of a fully credible vote.

### Example 2: Trusted Voter with Detailed Comment

**Scenario:**
- 90-day old account with +80 reputation
- Detailed comment (75 characters)
- First vote today
- Agrees with consensus 85% of the time

**Calculation:**
```
Base vote value: +1

Account age factor: min(1.0, 90/30) = 1.0
Voter reputation bonus: 1.0 + ((80-50)/100)×0.5 = 1.15
Spam dampener: 1.0 / (1 + 0×0.1) = 1.0
Comment quality: 1.3 (detailed)
Consensus agreement: 1.0 (85% is in 70-100% range)
Time decay: 1.0 (just voted)

Final weight: 1 × 1.0 × 1.15 × 1.0 × 1.3 × 1.0 × 1.0 = 1.495

Contribution to score: +1.495
```

**Interpretation:** Trusted voter with good feedback provides nearly 1.5× value.

### Example 3: Reciprocal Upvoting

**Scenario:**
- Two friends upvote each other within 5 minutes
- Both have decent credibility otherwise

**Calculation:**
```
Base vote value: +1
Other factors: 1.0 (assume perfect credibility)

Reciprocal penalty: 0.4 (within 1 hour)

Final weight: 1 × 1.0 × 0.4 = 0.4

Contribution to score: +0.4
```

**Interpretation:** Obvious vote trading is heavily penalized. The votes still count but at only 40% strength.

---

## Best Practices for Players

### To Be a Good Voter:
1. **Wait and observe** - Don't vote on day 1, build credibility first
2. **Vote diverse** - Don't only upvote or only downvote
3. **Add comments** - Detailed feedback is worth 30% more
4. **Be consistent** - Votes that align with eventual consensus build your credibility
5. **Vote thoughtfully** - Quality over quantity, avoid spam voting

### To Build Good Reputation:
1. **Be consistently positive** - Reputation reflects recent behavior via time decay
2. **Engage broadly** - Get votes from many different people
3. **Give detailed feedback** - When you vote on others, add comments
4. **Don't game the system** - Reciprocal voting and brigading are detected and penalized
5. **Participate long-term** - Older, more credible voters have more impact

### To Recover from Low Reputation:
1. **Change behavior** - Time decay means old votes eventually fade
2. **Get diverse positive votes** - Need votes from many different people
3. **Remember progressive bonuses** - Votes on low-reputation players count for more
4. **Be patient** - Reputation recovery takes time, similar to building it initially

---

## Technical Implementation

### Database Schema

**reputation_votes:**
- Stores individual votes with voter, target, value, comment, timestamp
- Unique constraint prevents duplicate votes from same voter to same target

**reputation_cache:**
- Caches calculated scores for performance
- Includes display score, percentile rank, last calculated time

**reputation_voter_stats:**
- Tracks credibility metrics per voter
- Updated on each vote and during consensus checks

### Key Classes

**ReputationManager:**
- Core calculation engine
- Implements all formulas and factors
- Handles vote submission and validation

**ReputationScheduler:**
- Background task coordinator
- Runs cache refresh, consensus tracking, percentile updates

**ReputationCommandListener (Discord):**
- Handles `/rep` slash commands in Discord
- Generates formatted embeds

**ReputationCommand (Velocity):**
- Handles `/rep` commands in-game
- Generates formatted text with tooltips

---

## Privacy & Security

### Anonymous Voting
- Voter names are never displayed when viewing reputation
- Only date and comment are shown
- Prevents retaliation or bias

### Vote History Protection
- Individual vote records are stored but not exposed publicly
- Only aggregated scores and anonymous comments are visible
- Prevents targeted harassment campaigns

### Anti-Manipulation
- Multiple overlapping detection systems
- No single factor can be gamed without triggering others
- Progressive penalties make mass manipulation expensive

### Data Integrity
- All votes are permanent records (for consensus tracking)
- Scores are recalculated from raw data, not incrementally updated
- No way to "edit" past votes to manipulate statistics

---

## Frequently Asked Questions

**Q: Why can't I vote on someone I just voted on?**
A: 7-day cooldown prevents spam voting and vote manipulation.

**Q: Why is my vote worth less than I expected?**
A: Check your account age, vote diversity, spam rate, and own reputation. New or one-sided voters have reduced weight.

**Q: Why did someone's reputation drop even with no new negative votes?**
A: Time decay - old positive votes are gradually losing weight.

**Q: Can I see who voted on me?**
A: No, votes are anonymous to prevent retaliation and bias.

**Q: How long until my reputation recovers?**
A: Depends on new votes and time. Old votes decay at ~50% every 30 days. New positive votes from diverse, credible voters help most.

**Q: Why do some + symbols appear bold and others don't?**
A: Bold means the voter included a comment. Regular means no comment (slightly lower weight).

**Q: Is there a limit to how high/low reputation can go?**
A: Display score is capped at -100 to +100 (via tanh normalization), but internal score can go higher/lower.

**Q: Can reputation affect gameplay?**
A: Not currently. It's purely a community indicator. Server admins could optionally use it for perks or restrictions.

---

## Credits

This reputation system is inspired by:
- Reddit's karma system (vote aggregation)
- Stack Overflow's reputation (credibility factors)
- Steam's review system (helpful/not helpful weighting)
- Academic citation networks (consensus and authority)

Designed to balance fairness, anti-abuse, and community building in Minecraft servers.
