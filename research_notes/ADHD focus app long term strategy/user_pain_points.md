# User Pain Points, Unmet Needs and Sentiment: Screen-Time Blockers, Focus Apps and ADHD Productivity Apps (2023–2026)

Method note: Research done 2026-11-22 via WebSearch only. Direct fetches failed with DNS errors for dev.to, medium.com, unscroll.io, appbrain.com, regainapp.ai (plus the known play.google.com / apps.apple.com / alternativeto.net failures). Many findings therefore come from search-engine summaries of pages rather than full-page reads; verbatim quotes are short and taken from those summaries. No primary Reddit threads were surfaced by search (Reddit appears poorly indexed by this search tool); Reddit sentiment is only available second-hand through aggregators and HN. Treat everything below as directional, and say "anecdote" unless a count is given.

## 1. What users say about Regain specifically (ai.regainapp, EpowerX Labs)

### Takeaway
Regain's public reviews are dominated by "it actually works" praise for Strict Mode (large self-reported screen-time drops), with a minority of complaints that cluster on (a) the move of Strict Mode/Pomodoro behind a Pro paywall after the app "started as completely free", (b) at least one claimed Strict Mode loophole, and (c) a weaker iOS port. Android rating is high on a large base; iOS is tiny and negative.

### Cited Findings
- Store listings now carry the name "Regain: Study Timer for Focus" (iOS id6756349667 and Play, en_IN/en_CA), while an older Play title "Regain: Screen time + Focus" still shows in the US index, which confirms the pivot from screen-time-control branding to study-timer branding. — [App Store](https://apps.apple.com/in/app/regain-study-timer-for-focus/id6756349667); [Google Play US](https://play.google.com/store/apps/details?id=ai.regainapp&hl=en&gl=US); [Google Play IN](https://play.google.com/store/apps/details?id=ai.regainapp&hl=en_IN)
- The Play IN listing shows about 2.37 lakh (~237,000) reviews at about 4.5 stars, per a search summary. AppBrain lists 4.49/5. — [Google Play IN](https://play.google.com/store/apps/details?id=ai.regainapp&hl=en_IN); [AppBrain](https://www.appbrain.com/app/regain-screen-time-focus/ai.regainapp)
- iOS: 2.8/5 from only 4 ratings. Complaints there: the layout doesn't fit an iPhone SE screen ratio, and the app is a "downgraded version" compared with Android. — [App Store reviews](https://apps.apple.com/us/app/6756349667?see-all=reviews&platform=iphone)
- Developer claims: once a Strict Mode session starts you "can't end it early or uninstall Regain until the timer runs out", with "genuinely no loophole". The Play listing adds uninstall prevention plus floating-window and split-screen blocking. — [regainapp.ai](https://regainapp.ai/); [Google Play](https://play.google.com/store/apps/details?id=ai.regainapp&hl=en_CA)
- Paywall: the App Store lists Regain Pro as unlocking Pomodoro, Strict Mode, Premium Themes and "Science-Backed Focus Music". A Play reviewer complained it "started as a completely free focusing app". The developer replied that Pro "helps us maintain and improve stronger blocking features". Another snippet notes that in the free version you can "easily pause or stop focus sessions and access distracting apps", so the free tier lacks the commitment device users actually want. — [App Store](https://apps.apple.com/in/app/regain-study-timer-for-focus/id6756349667); [Google Play IN](https://play.google.com/store/apps/details?id=ai.regainapp&hl=en_IN)
- Praise (anecdotes): screen time went "from 7-8 hrs to 2-3 hrs" after five days; "strict mode was the best"; "extremely wonderful for limiting screen time". — [Google Play / App Store via search summary](https://play.google.com/store/apps/details?id=ai.regainapp&hl=en&gl=US)
- Reliability/bypass complaints (anecdotes): one Android reviewer called Strict Mode a "critical loophole that makes it useless" (no details). Another said it "won't let me view my screentime stats while I'm having it block Chrome", meaning over-blocking hides the app's own feedback loop. — [Google Play via search summary](https://play.google.com/store/apps/details?id=ai.regainapp&hl=en&gl=US)
- A Medium user post titled "From Doomscrolling to Digital Freedom: The App That Actually Worked — REGAIN" exists (2024–26). Its full text could not be loaded. — [Medium](https://medium.com/@ujjwalgupta8447/from-doomscrolling-to-digital-freedom-the-app-that-actually-worked-regain-0f892fd67aef)
- Name collision: search results for "Regain reviews" are swamped by unrelated products: Regain.us couples therapy (Trustpilot/PissedConsumer billing complaints), "Regain: Focus & Control" by Apexmart (ai.regainapp.focus), and a removed "Regain: Screen time control" (com.ai.screentime.control.regainapp). — [Trustpilot](https://www.trustpilot.com/review/regain.us); [AppBrain Apexmart](https://www.appbrain.com/app/regain-focus-control/ai.regainapp.focus); [AppBrain removed app](https://www.appbrain.com/app/regain-screen-time-control/com.ai.screentime.control.regainapp)

### Inferences
- The large Android review base with a high average suggests strong product-market fit with an India-heavy student audience (en_IN listing, "lakh" figures). The pivot to "Study Timer" matches where the traction is.
- Paywalling the core commitment mechanism (Strict Mode) runs into the most common complaint across the whole category (see section 2): users feel bait-and-switched when a free blocker becomes paid. With an Indian student base, a ~$10/mo-equivalent price is likely to face strong price resistance (see section 5).
- Brand searchability is a real problem. "Regain" collides with a well-known therapy brand that has negative billing reviews.

### Gaps
- Could not verify the "~$10/mo" Strict Mode price. No source surfaced Pro pricing.
- No user commentary on the Rega mascot was found anywhere.
- No Reddit threads mentioning Regain surfaced. Could not read the full Play reviews (DNS block) or the Medium post.
- Couldn't determine the specifics of the "critical loophole" (likely Android-level bypasses such as safe mode, clock change or ADB, but that's unverified).

## 2. Common complaints across competitors (ScreenZen, Opal, one sec, AppBlock, Forest, Tiimo, Structured, Inflow, Focusmate)

### Takeaway
Across the category, the dominant complaint is bypassability (users "outsmart" soft blocks on autopilot). Next come subscription/paywall resentment and reliability bugs (blocks failing or misfiring, crashes, slowness). Battery drain is rarely evidenced. Abandonment is high in adjacent app categories, but blocker-specific retention data was not found.

### Cited Findings
**Quantified pattern (one app, one dataset):**
- A dev.to analysis of 1,355 App Store reviews of SocialLite (Reels/Shorts blocker), Feb–Sep 2026: 466 were 1–3 stars. The top low-star complaint was performance (slow, heavy or crashing): 143 reviews, 30.7% of low-star reviews. 266 reviews (19%) contained a feature request. Written reviews averaged 3.79 stars versus a 4.76 storefront average (7,317 ratings), so star averages overstate satisfaction. Caveats: the sample size is inconsistent within the article (1,355 vs 1,405), and the author promotes a competing app (minulo). — [dev.to](https://dev.to/ayumu_kitabayashi_8e0de96/what-1355-app-store-reviews-say-people-want-from-a-reelsshorts-blocker-2026-data-4mf); [author's competing-app article](https://dev.to/ayumu_kitabayashi_8e0de96/a-reels-blocker-that-doesnt-ask-for-money-in-setup-what-you-can-hide-on-iphone-before-paying-35jh)
- The same author also wrote about blockers that ask for money during setup, which points to onboarding paywalls as a pain point in the category. — [dev.to](https://dev.to/ayumu_kitabayashi_8e0de96/a-reels-blocker-that-doesnt-ask-for-money-in-setup-what-you-can-hide-on-iphone-before-paying-35jh)

**Opal:**
- Reddit user quoted by an aggregator (377 Reddit comments + 1,799 reviews analysed) calls Opal "too easy to bypass" but keeps it as a second layer. The aggregator reports repeated complaints that annual pricing is poor value relative to the free tier. Deep Focus and unlimited schedules are Pro-only. — [unscroll.io (via search)](https://www.unscroll.io/alternatives-to/opal-screen-time-app)
- A 2026 review says a Mac bypass bug from 2025 is still unfixed and that the "unbypassable" hardest mode can be bypassed by logging out. Single source, from a competitor (Hugo). — [tryhugo.app](https://www.tryhugo.app/blog/opal-app-review-2026)
- Other reviews note "shady tactics" to push subscriptions, bugs when adding apps to block, and that Opal can be switched off in iOS Screen Time settings. — [floviu](https://floviu.com/blog/opal-app-review/); [Headway](https://makeheadway.com/blog/opal-app-review/)

**ScreenZen / one sec / AppBlock:**
- ScreenZen bugs: a 4-star Play review mentions "mild bugs" and days where it fails to block until a midnight reset. Another Play reviewer said the block screen fired "every minute or two" despite a 7-minute unlock (the dev replied it was fixed). Aggregators report inconsistent website blocking and permission bugs. — [Google Play](https://play.google.com/store/apps/details/ScreenZen_App_Blocker?id=com.screenzen&hl=en-US); [unhookd](https://unhookd.app/blog/screenzen-worth-it-review); [Kohru](https://www.kohruapp.com/blog/screenzen-app-review)
- ScreenZen bypass: an Android clock-change workaround resets limits, and delays "can be waited out" by a determined user. A competitor ranking rates bypass difficulty as Low for ScreenZen and one sec and Medium for AppBlock. — [usemado](https://usemado.app/blog/screenzen-review); [getbroc](https://getbroc.app/best-app-blockers-android/)
- Battery: no user evidence of significant drain for ScreenZen. For AppBlock, only marketing claims were found. — [Nibble](https://nibble-app.com/blog/screenzen); [SaaSHub](https://www.saashub.com/compare-appblock-vs-one-sec)
- Reels-blocker reviews (ScrollGuard): "blocking showed right back up in the app after a few minutes", and requests for more platforms ("can't wait for more app support: Facebook, LinkedIn"). — [ScrollGuard App Store reviews](https://apps.apple.com/us/app/scrollguard-block-reels/id6754183872?see-all=reviews&platform=iphone)

**Inflow / Tiimo (ADHD-specific planners/CBT):**
- Inflow App Store reviews: a short trial then an annual charge was called an "intentional ADHD tax" and "scummy" (2022). A 2024 reviewer said the app "seems to target people with ADHD". Another 2024 reviewer said the price is hard to justify because much of the content is free online. — [Inflow App Store reviews (PL)](https://apps.apple.com/pl/app/inflow-manage-your-adhd/id1528183849?see-all=reviews&platform=iphone)
- Tiimo pricing is reported inconsistently ($4–5, $7.99 or $12/mo), which is itself a confusion signal. A free mobile tier exists. — [Saner.ai](https://www.saner.ai/blogs/best-tiimo-alternatives); [FOCO](https://www.tryfoco.com/best-adhd-planner/)
- FOCO's review of Chillio/Aligned names unexpected subscription charges and checkout upsells as the main complaints, calling surprise charges "a classic ADHD tax". — [FOCO](https://www.tryfoco.com/chillio-app-review/)

**Focusmate / body doubling:**
- Focusmate's own survey of 212 ADHD users claims a "152 percent productivity increase". It's self-reported and company-sponsored. Barriers: anxiety about meeting strangers on video, and some (e.g. autistic users) find a body double distracting. — [CHADD Substack](https://chaddhelp4adhd.substack.com/p/could-a-body-double-help-you-increase); [Healthline](https://www.healthline.com/health/adhd/body-double-adhd); [Wikipedia: Body doubling](https://en.wikipedia.org/wiki/Body_doubling)

**Abandonment (adjacent evidence):**
- In a thematic analysis of 13,549 reviews across 106 mental health apps, poor usability was "the most common reason for app abandonment". — [Alqahtani & Orji 2020](https://doi.org/10.1177/1460458219896492)
- Adult ADHD app audit: 18.2k downloads, 12k active users, 6.4k returning. App burden for ADHD users was flagged. — [PMC11847728](https://pmc.ncbi.nlm.nih.gov/articles/PMC11847728)
- Adult ADHD CBT app trial: 52.7% retention at week 7 (108/205). — [PLOS Digital Health](https://journals.plos.org/digitalhealth/article?id=10.1371%2Fjournal.pdig.0000083)
- Mental-health apps: 7-day retention of 5.5%–19.1%. Some drop-off is "happy abandonment". — [PMC8844980](https://pmc.ncbi.nlm.nih.gov/articles/PMC8844980/)
- An ACM systematic review of digital self-control tools calls for research on reducing user abandonment. — [ACM 10.1145/3571810](https://dl.acm.org/doi/10.1145/3571810)

### Inferences
- The hierarchy of pain is: (1) "I bypass it", (2) "it broke or failed to block", (3) "they want a subscription for the part that works". Regain's reviews mirror all three, which suggests these complaints come with the category rather than being unique to Regain.
- Storefront star ratings are inflated by in-app rating prompts. Written-review sentiment (about 3.8 vs 4.8) is a better gauge of real satisfaction.

### Gaps
- No data found for Forest or Structured complaints specifically, or on shame/streak backlash. These are known themes in the space, but this session found no citable evidence.
- No blocker-specific retention curves (e.g., the share of people still using Opal or one sec after 4 weeks).
- Battery complaints are essentially unevidenced.

## 3. What ADHD communities say they need

### Takeaway
ADHD users consistently say soft friction fails because they bypass it impulsively, "on autopilot". They value hard commitment (no override, physical separation such as Brick or a timer safe, random passwords), automatic or scheduled blocking that doesn't depend on starting a session, external accountability (body doubling), and fair pricing without trial-to-annual traps.

### Cited Findings
- HN commenter with ADHD: they tried app blockers and "they all fail because the urge to procrastinate is so great", getting past them on autopilot. Another HN commenter: people "just bypass the limit instantly as it pops up". — [HN: Ask HN YouTube addiction](https://news.ycombinator.com/item?id=45085014); [HN: guide to reduce screen time](https://news.ycombinator.com/item?id=43630661)
- HN: a physical blocker like Brick and/or a Kitchen Timer Safe were the only setups that kept working over time. Having someone else set a random Screen Time password makes the limit "a real hassle to circumvent". — [HN](https://news.ycombinator.com/item?id=43630661); [HN Show HN 'why?' app](https://news.ycombinator.com/item?id=42254156)
- An ADHD blocker guide argues that time limits with bypass buttons get overridden "in seconds" because the ADHD brain seeks "the path of least resistance to dopamine". It also argues that blockers requiring you to start a session fail because starting needs the executive function ADHD impairs (task initiation). Vendor-adjacent blog. — [habitdoom](https://habitdoom.com/blog/best-app-blocker-adhd)
- Brick (~$59): reviewers say the physical tap-to-unlock works when the puck is kept far away. An NBC tester kept it "as far away from me as possible" because otherwise it's "too easy to unBrick". Consumer Reports' writer found it harder to bypass than OS limits. Weaknesses: only 5 emergency unbricks, phone-only (users pair it with Cold Turkey on desktop), and it "won't generate discipline you're not interested in practicing". — [NBC Select](https://www.nbcnews.com/select/shopping/brick-phone-app-blocker-review-rcna259740); [Consumer Reports](https://www.consumerreports.org/electronics-computers/cell-phones/can-brick-solve-my-screen-time-problem-a3152096219); [Scribbright](https://scribbright.com/brick-phone-blocker-review/)
- A BestSelf piece frames a physical device as "external executive function" for ADHD (promotional, first-person). — [BestSelf](https://bestself.co/pages/content/the-physical-app-blocker-that-my-adhd-brain-cant-outsmart)
- "ADHD tax" framing for subscriptions: Inflow reviews ("intentional ADHD tax") and FOCO ("surprise charges... a classic ADHD tax"). ADDA lists tools for adults and defines the ADHD tax as the extra money, time and emotional energy lost to symptoms. — [Inflow reviews](https://apps.apple.com/pl/app/inflow-manage-your-adhd/id1528183849?see-all=reviews&platform=iphone); [FOCO](https://www.tryfoco.com/chillio-app-review/); [ADDA](https://add.org/adhd-tools-for-adults/)
- Body doubling is widely endorsed in ADHD content, but the evidence is thin (mostly master's theses; one 2024 study cited as positive for task initiation and completion). — [Wikipedia](https://en.wikipedia.org/wiki/Body_doubling); [Healthline](https://www.healthline.com/health/adhd/body-double-adhd)

### Inferences
- For ADHD users, Regain's Strict Mode (non-cancellable, uninstall-proof) is the right core mechanic. Gating it entirely behind Pro means ADHD users only experience the version they say fails (the easily pausable free tier).
- Scheduled or automatic Strict sessions (no initiation required) and lightweight social accountability (study rooms or body-doubling) are the clearest unmet needs relative to a pure timer.

### Gaps
- No primary posts from r/ADHD, r/adhdwomen, r/ADHD_Programmers, r/nosurf, r/digitalminimalism or the ADDitude forums were retrievable. Themes like time blindness, shame around streaks, and onboarding complexity could not be cited directly from these communities.

## 4. Student-specific needs (exam prep, India JEE/NEET, college)

### Takeaway
Indian exam-prep students are an explicitly targeted segment. Competing blockers (Forest, Stay Focused, Beave) market directly to JEE/NEET aspirants with Pomodoro plus scheduled blocks for coaching and self-study hours. The organic student advice is often low-tech ("give your phone to your mother"), which signals a demand for external enforcement.

### Cited Findings
- Forest's IN Play listing pitches to JEE/NEET aspirants: a Pomodoro timer plus app locking, with "Time Guard" scheduled blocks for coaching classes or self-study, keeping study apps accessible. — [Forest Play IN](https://play.google.com/store/apps/details?id=cc.forestapp&hl=en_IN)
- Stay Focused targets JEE/NEET students with scheduled auto-block during "class" (requests Device Admin). Beave lists JEE/NEET/CAT/GMAT/GRE/MCAT and adds a "breathe before you open" delay. — [Stay Focused](https://play.google.com/store/apps/details?id=com.stayfocused&hl=en_IN); [Beave](https://play.google.com/store/apps/details?id=com.aktarstudio.taskpia&hl=en_US)
- Careers360 forum (JEE/NEET aspirants asking how to stop phone addiction) suggests giving your mobile to a family member during study hours. NEET coaching blogs recommend app blockers. — [Careers360 NEET](https://www.careers360.com/question-how-to-stop-our-habit-of-phone-addiction-during-studies-how-to-concentrate-on-studies-rather-than-mobile-i-m-neet-aspirant); [Careers360 JEE](https://www.careers360.com/question-how-to-stop-ur-phone-addiction-i-am-jee-aspirant/amp); [Meiosis](https://meiosispublications.com/exams/breaking-free-effective-strategies-to-overcome-phone-addiction-for-neet-aspirants/38274/); [S2 Academy](https://s2academy.in/how-to-stop-phone-addiction-during-neet-preparation-2026-powerful-digital-detox-tips-for-aspirants/)
- Students need study apps (lecture videos, PDFs, YouTube lectures) to stay usable while distracting apps are blocked, as Forest's "keep essential study apps accessible" positioning shows. — [Forest Play IN](https://play.google.com/store/apps/details?id=cc.forestapp&hl=en_IN)

### Inferences
- A JEE/NEET student needs YouTube for lectures but must avoid Shorts, so granular blocking (Shorts/Reels inside YouTube/Instagram, rather than whole-app blocks) is likely a high-value feature. The Reels-blocker category's growth supports this.
- Parents act as enforcers ("give phone to mother"). A parent-set or parent-held lock is a natural extension.

### Gaps
- No citable data on YPT (Yeolpumta) or study-room/leaderboard demand among Indian students, and no college-student-specific (US) complaint data.

## 5. Willingness to pay and price sensitivity

### Takeaway
Users resist subscriptions in this category, especially when the "working" feature is paywalled, trials auto-convert to annual plans, or payment is asked for during onboarding. One-time purchases and hardware (Brick ~$59) are positioned as the anti-subscription alternative. Price points span from a one-time purchase to $99.99/yr.

### Cited Findings
- Price anchors: Brick ~$59 one-time hardware. A "Screen Time Control" app at $99.99/yr auto-renewing. #blockit sells single-site unlocks ("Unlock Reddit $1.99"). Hold It markets itself as a one-time purchase with no subscription, explicitly against monthly or yearly apps. — [Scribbright](https://scribbright.com/brick-phone-blocker-review/); [App Store Screen Time Control](https://apps.apple.com/us/app/app-blocker-limit-screen-time/id6502186144?l=ar); [#blockit](https://apps.apple.com/us/app/-/id1492879257); [Hold It](https://apps.apple.com/cn/app/id6745756356)
- HyperDo puts its blocker, widgets and lock-screen banner behind a subscription. — [HyperDo](https://apps.apple.com/ca/app/hyperdo-study-timer/id1564457070)
- Opal: repeated complaints about annual price versus the free tier, plus "shady tactics" to subscribe. — [unscroll.io](https://www.unscroll.io/alternatives-to/opal-screen-time-app); [Headway](https://makeheadway.com/blog/opal-app-review/)
- Inflow: trial-to-annual charge called an "ADHD tax"; "much of the content is free online". — [Inflow reviews](https://apps.apple.com/pl/app/inflow-manage-your-adhd/id1528183849?see-all=reviews&platform=iphone)
- A dev.to article on "a Reels blocker that doesn't ask for money in setup" shows that paywall-at-onboarding is a recognised irritant. — [dev.to](https://dev.to/ayumu_kitabayashi_8e0de96/a-reels-blocker-that-doesnt-ask-for-money-in-setup-what-you-can-hide-on-iphone-before-paying-35jh)
- Regain: "started as a completely free focusing app" (complaint about the Pro move). — [Google Play](https://play.google.com/store/apps/details?id=ai.regainapp&hl=en_IN)

### Inferences
- For an India-heavy student base, a US-style ~$10/mo price is likely far above tolerance. Regional pricing, a cheap annual or exam-season plan, or a lifetime option would reduce "bait-and-switch" sentiment. This is an inference only; no Indian willingness-to-pay data was found.
- Users seem more willing to pay for commitment they can't undo (Brick, strict modes) than for extras like themes and music, which supports separating "strictness" from "cosmetics" in pricing.

### Gaps
- No survey data on willingness to pay for focus/ADHD apps, and no conversion-rate benchmarks. Regain's actual Pro price was not verified.
