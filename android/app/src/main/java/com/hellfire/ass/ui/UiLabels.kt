package com.hellfire.ass.ui

import com.hellfire.ass.R

/** Resource lookup for existing interface labels; never applied to captured user content. */
object UiLabels {
    fun id(text: String): Int? = when (text) {
        "Home" -> R.string.ui_3a78695388
        "Money" -> R.string.ui_5ccc2e8715
        "Ask" -> R.string.ui_b8c209cdea
        "Tasks" -> R.string.ui_b3a60e61a5
        "Task" -> R.string.ui_4bc74b2135
        "Inbox" -> R.string.ui_94835ea2fc
        "Settings" -> R.string.ui_74a883a037
        "Review" -> R.string.ui_aff0766a52
        "Daily review" -> R.string.ui_8c64fa44d7
        "Review next" -> R.string.ui_65e3a57008
        "Review payment" -> R.string.ui_80712e2f2d
        "Review task" -> R.string.ui_170da5e1b5
        "Review reply in Inbox" -> R.string.ui_09a08c5602
        "Review pending payments" -> R.string.ui_40a3b25c20
        "Payments, tasks and replies in one place. Nothing is posted, scheduled or sent without its own approval." -> R.string.ui_bb3969ce41
        "Next · payment" -> R.string.ui_4ed36bd325
        "Next · task" -> R.string.ui_09f873ccaa
        "Next · reply" -> R.string.ui_cba2fe493c
        "You're caught up" -> R.string.ui_e68cbfcf62
        "There are no payments, task proposals or drafts waiting for review." -> R.string.ui_a9e82cdb53
        "Cash check-in" -> R.string.ui_aa025bdd64
        "I have finished these cash check-ins" -> R.string.ui_b1d36a4a1f
        "Set account/category for pending payments…" -> R.string.ui_06268fd2da
        "Suggest account and category" -> R.string.ui_1e96751ace
        "Choose another payment" -> R.string.ui_f7d5b1abad
        "Add cash" -> R.string.ui_bc994a88a0
        "Add cash spending" -> R.string.ui_3e1baf33e3
        "Add task" -> R.string.ui_ba423a4640
        "New task" -> R.string.ui_3e992276b2
        "Edit task" -> R.string.ui_cf1368a3d1
        "Today" -> R.string.ui_2b065c7c9c
        "Upcoming" -> R.string.ui_5f1a2542e4
        "Done" -> R.string.ui_11a6767d56
        "Not done yet" -> R.string.ui_2cb1983a1e
        "Undo" -> R.string.ui_a8283ade31
        "Reschedule" -> R.string.ui_a6a80431b0
        "Repeat" -> R.string.ui_b6b7a00658
        "Daily" -> R.string.ui_b36c2611dc
        "Weekly" -> R.string.ui_2975132481
        "Monthly" -> R.string.ui_9b11f6b707
        "None" -> R.string.ui_dc937b5989
        "Keep reminding" -> R.string.ui_f481a73cda
        "Hourly" -> R.string.ui_eab0cd8fdf
        "Overdue reminder intensity" -> R.string.ui_b41cc8f2fd
        "Repeats every month" -> R.string.ui_5867e278dc
        "Repeating tasks schedule the next occurrence when you mark this one done. Keep reminding uses your overdue interval in Settings." -> R.string.ui_85b2286585
        "Reminders" -> R.string.ui_7971dd7526
        "Reminder" -> R.string.ui_31757bbfa1
        "Overdue" -> R.string.ui_d660945bc2
        "Overdue reminders" -> R.string.ui_051cc2b13b
        "Allow reminder notifications" -> R.string.ui_cf8e642432
        "Manage exact-alarm access" -> R.string.ui_451cd39654
        "Nightly cash review" -> R.string.ui_3fac58d0c7
        "Morning brief" -> R.string.ui_c6aa9cd74d
        "Evening wrap-up" -> R.string.ui_f296e45acc
        "Capture problem alerts" -> R.string.ui_509d03586b
        "Save interval" -> R.string.ui_d0a300f3a4
        "Access" -> R.string.ui_ec5ba0abb7
        "Kind" -> R.string.ui_f5387f9bb6
        "Type" -> R.string.ui_baaddf70fb
        "Promise" -> R.string.ui_1eec97a07f
        "Who does it" -> R.string.ui_86ebc563f5
        "Me" -> R.string.ui_d30af076b0
        "Someone else" -> R.string.ui_3233cb049a
        "Not sure" -> R.string.ui_dca5fa430c
        "Bill to pay" -> R.string.ui_9c10e38867
        "I owe" -> R.string.ui_87dbf39d87
        "Owed to me" -> R.string.ui_49aa2eb928
        "Neither" -> R.string.ui_9957d09c21
        "Good morning" -> R.string.ui_90a90a48e2
        "Good afternoon" -> R.string.ui_d325e1bbfd
        "Good evening" -> R.string.ui_15a421e4cf
        "Needs you" -> R.string.ui_74b6abdf59
        "Spent today" -> R.string.ui_baddce6d8b
        "Received today" -> R.string.ui_7c9f7617b7
        "Up next" -> R.string.ui_e31da5b25f
        "All tasks" -> R.string.ui_cb66482383
        "Only payments you confirmed count here." -> R.string.ui_7f6c052da7
        "Templates" -> R.string.ui_56b564b75c
        "Use a template" -> R.string.ui_d7942d2821
        "Waiting on" -> R.string.ui_7ab15c8cd5
        "People" -> R.string.ui_7db2089705
        "Debts" -> R.string.ui_3b387c6547
        "Bills" -> R.string.ui_245ad028f7
        "Record" -> R.string.ui_bfdd510698
        "Recordings" -> R.string.ui_60c56d66a1
        "Reports" -> R.string.ui_dacca3cba3
        "Report" -> R.string.ui_b6ce788d97
        "Full report" -> R.string.ui_f3e23734b2
        "Calendar" -> R.string.ui_d5d0a30b51
        "Add to calendar…" -> R.string.ui_c4e7ea5310
        "Source" -> R.string.ui_0e570ca6fa
        "Sources" -> R.string.ui_caf85b0888
        "Show source" -> R.string.ui_95a84d695e
        "Remember this" -> R.string.ui_11cbee289f
        "Save memory" -> R.string.ui_26ddf55db7
        "Create task" -> R.string.ui_6f541e1b25
        "Choose an exact passage from the source. It stays linked to the original." -> R.string.ui_c78a983b05
        "Person or subject" -> R.string.ui_b6b19ae750
        "Source excerpt" -> R.string.ui_fc537eec08
        "Ask about your money, tasks and messages" -> R.string.ui_fbaa34ca38
        "Ask history" -> R.string.ui_ee7227ce35
        "History" -> R.string.ui_0e76960093
        "Keep encrypted history" -> R.string.ui_0e15ae8690
        "Clear history and conversation" -> R.string.ui_28c7029cdb
        "Who answers" -> R.string.ui_202610b579
        "On this phone" -> R.string.ui_f97da48734
        "Local model + saved sources" -> R.string.ui_b321be0264
        "AI task" -> R.string.ui_57f3f6c9f3
        "Cloud AI" -> R.string.ui_457588a8c6
        "On-device models" -> R.string.ui_2c7e25e017
        "Monthly AI budget" -> R.string.ui_ac6c80d635
        "Confirm" -> R.string.ui_eebdd24a77
        "Completed" -> R.string.ui_22a970d2e5
        "Applying…" -> R.string.ui_3329a9bb48
        "Could not complete. Check the result below before trying again." -> R.string.ui_61a99938eb
        "Discard" -> R.string.ui_eb1a70e392
        "Discarded" -> R.string.ui_7fb156411a
        "Confirming saves a draft. Sending still needs Approve and send in Inbox." -> R.string.ui_dd73221dc2
        "Only confirmed payments are counted. Own transfers are excluded." -> R.string.ui_5e533b4731
        "The saved records this answer used. Tap one to see the original." -> R.string.ui_aa257cd947
        "Working…" -> R.string.ui_5474eef8d0
        "Cancel" -> R.string.ui_19766ed6cc
        "Edit" -> R.string.ui_464c4ffd01
        "Save" -> R.string.ui_1509f561f2
        "Continue" -> R.string.ui_31fbef1625
        "Skip for now" -> R.string.ui_b58eb52c88
        "Back" -> R.string.ui_76900f1bfd
        "Turn on" -> R.string.ui_5a1f096a0d
        "Turn off" -> R.string.ui_06f0e210b2
        "On" -> R.string.ui_1300117561
        "Off" -> R.string.ui_ca7981b46e
        "Email" -> R.string.ui_969ccbd3cf
        "WhatsApp" -> R.string.ui_6a40edf1fc
        "All" -> R.string.ui_a52ace420f
        "Calls" -> R.string.ui_b73a5e2ca6
        "SMS" -> R.string.ui_40b601a10c
        "Conversations" -> R.string.ui_1d432f5869
        "All items" -> R.string.ui_5110768675
        "Load older history" -> R.string.ui_5894fff79e
        "Search all saved messages" -> R.string.ui_5ffa922919
        "Search checks saved message bodies and the full source-text index, including older captures." -> R.string.ui_ece2a57619
        "Approve and send" -> R.string.ui_8a997164d2
        "DRAFT READY" -> R.string.ui_56259e319e
        "Sent and failed drafts" -> R.string.ui_222753ee56
        "Email and WhatsApp accounts are set up in Settings. Nothing is sent without your approval." -> R.string.ui_ca48e69a9c
        "No reply needed" -> R.string.ui_12e62269e9
        "Tomorrow" -> R.string.ui_456a73bbce
        "Category" -> R.string.ui_292c06f004
        "Account" -> R.string.ui_7e1b0d5641
        "Accounts" -> R.string.ui_8a7c8b67fe
        "Amount TZS" -> R.string.ui_aa9b4b5f1b
        "Fee TZS" -> R.string.ui_cb88202b9f
        "Description" -> R.string.ui_526e0087cc
        "Merchant" -> R.string.ui_1da9a04272
        "Name this merchant" -> R.string.ui_6675fa6b7f
        "Usual category" -> R.string.ui_66e97ac375
        "Change category" -> R.string.ui_2b31046ef3
        "To confirm" -> R.string.ui_091fd6e985
        "Recent" -> R.string.ui_690dbe9dc0
        "Total balance · TZS" -> R.string.ui_9c823cdc78
        "Name" -> R.string.ui_dcd1d5223f
        "Food" -> R.string.ui_e4eb18069d
        "Groceries" -> R.string.ui_2742b7b24a
        "Transport" -> R.string.ui_aaead4abf5
        "Fuel" -> R.string.ui_a80f942f41
        "Utilities" -> R.string.ui_0a035c2b85
        "Rent" -> R.string.ui_f0268cb910
        "Business" -> R.string.ui_76f68a75f0
        "Family" -> R.string.ui_bd2d677b2e
        "Airtime" -> R.string.ui_27b54406ac
        "Income" -> R.string.ui_51595db919
        "Fees" -> R.string.ui_6d80486cc3
        "Other" -> R.string.ui_f97e9da0e3
        "Expense" -> R.string.ui_a0417ba801
        "Refund" -> R.string.ui_6b37bd3508
        "Transfer" -> R.string.ui_dde8bef78c
        "Own transfer" -> R.string.ui_12030ceac0
        "Only confirmed payments count toward your totals." -> R.string.ui_94a26c5abe
        "Not a payment" -> R.string.ui_50f18d4f05
        "Not a task" -> R.string.ui_bc9eb8587a
        "Remember this name and category" -> R.string.ui_4d77119a6f
        "Remember my account and category for this sender/merchant" -> R.string.ui_2ec5445000
        "Account/category suggested from your saved choice for this sender or merchant. Check them before confirming." -> R.string.ui_64e48f1350
        "Add an account in Money › Accounts first." -> R.string.ui_56df739b40
        "Add an account in Money › Accounts to record spending." -> R.string.ui_80e5be7c48
        "Add your M-Pesa, bank and cash accounts to track balances." -> R.string.ui_162aeba2fa
        "Check account balance" -> R.string.ui_8725a78f26
        "Before making an adjustment" -> R.string.ui_82624c4437
        "Latest observed SMS balances" -> R.string.ui_ee6ea96032
        "The two balances agree at this time." -> R.string.ui_54b97c06ac
        "Pick a receipt only if it belongs to this account. A provider name alone does not identify your account." -> R.string.ui_79f2ed6f2a
        "Record a reviewed balance adjustment…" -> R.string.ui_44c32f7da6
        "An adjustment records the difference with your approval and evidence. It never fabricates missing payments or counts as income/spending." -> R.string.ui_105fff40c9
        "Shared with HellFire" -> R.string.ui_bf59ac9cc3
        "Share text, a screenshot, document or audio file to HellFire from another app." -> R.string.ui_38fd328d04
        "Review this share before saving. Nothing is sent or posted to your ledger." -> R.string.ui_0279d4ac56
        "Shared text" -> R.string.ui_3d9b693b09
        "Save note" -> R.string.ui_6501e1ced8
        "Create reminder" -> R.string.ui_919e271a39
        "Review expense" -> R.string.ui_74c3f616fa
        "Save shared content" -> R.string.ui_ea7df3b344
        "Saving…" -> R.string.ui_23e39291d6
        "Only the first 60,000 characters are shown and saved." -> R.string.ui_285556b919
        "Private by default" -> R.string.ui_f52e06762e
        "Permissions and capture" -> R.string.ui_97de5a90ae
        "Capture status" -> R.string.ui_c6f448a169
        "Notifications" -> R.string.ui_788011833a
        "Microphone" -> R.string.ui_186352a881
        "On-time reminders" -> R.string.ui_889eda1fc5
        "Phone assistant" -> R.string.ui_14f7901e72
        "Run in background" -> R.string.ui_c7b156d319
        "Fingerprint app lock" -> R.string.ui_85330cea66
        "Encrypted backup" -> R.string.ui_0e6b09c31f
        "Memory and proposals" -> R.string.ui_4d2e102158
        "Home screen widgets" -> R.string.ui_4dbd32bfaf
        "Web access" -> R.string.ui_6890ecb87b
        "This device" -> R.string.ui_d052579c6a
        "Assistant" -> R.string.ui_391e405152
        "Let's set up HellFire" -> R.string.ui_e52ac334c1
        "about 1 minute" -> R.string.ui_1c0fe2d047
        "Finish the nightly review" -> R.string.ui_0be16ca9f4
        "Notification processing" -> R.string.ui_6fddf5bf12
        "Resume processing" -> R.string.ui_2c4517af49
        "Sync SMS history" -> R.string.ui_e8647e37c8
        "Turn off SMS capture" -> R.string.ui_b1bbbbd540
        "Every accessible notification is kept. Turning an app off keeps its future notifications as capture only." -> R.string.ui_2678e27d0d
        "Testing and cleanup" -> R.string.ui_5cc57b8088
        "Test the assistant session" -> R.string.ui_1f7a6ec5ca
        "Insert a synthetic test event" -> R.string.ui_854337d439
        "Delete captured events…" -> R.string.ui_092d731328
        "Save name" -> R.string.ui_b7297226fd
        "Open assistant settings" -> R.string.ui_44fda95629
        "Choose HellFire as the digital assistant" -> R.string.ui_eec735c5d7
        "Language" -> R.string.ui_a4fe65264e
        "Interface and voice language" -> R.string.ui_b3dda1bbc2
        "English" -> R.string.ui_ba118bf7fc
        "Kiswahili" -> R.string.ui_a7a38d4353
        "What to remember" -> R.string.ui_e970151b49
        "Text" -> R.string.ui_71988c4d8e
        "Subject" -> R.string.ui_6897128384
        "Message" -> R.string.ui_2f77668a9d
        "To · email address" -> R.string.ui_337a0c347e
        "To · WhatsApp number with country code" -> R.string.ui_40770c3719
        "Person (optional)" -> R.string.ui_cee28d2048
        "Person" -> R.string.ui_6007db63e1
        "Due · East Africa Time" -> R.string.ui_e1ef9979ed
        "Date and time · East Africa Time" -> R.string.ui_42d044abea
        "Balance as of · East Africa Time" -> R.string.ui_3c63a1af11
        "Balance shown by your provider · TZS" -> R.string.ui_954a073087
        "Amount TZS (optional, not added to Money)" -> R.string.ui_9f75e749b4
        "Snooze minutes · 5–1440" -> R.string.ui_ea0a75c1b1
        "Source message" -> R.string.ui_ec90df3eb4
        "Your cash entry" -> R.string.ui_b3802b51c0
        "Current balance TZS (optional)" -> R.string.ui_e7e3f27048
        "Account name, e.g. M-Pesa or CRDB Current" -> R.string.ui_fd5ad0b496
        "Email address" -> R.string.ui_f2488fd4ef
        "App password / IMAP password" -> R.string.ui_32bd9cb2ce
        "New password (leave blank to keep)" -> R.string.ui_fe0534116f
        "Read payment and message alerts. Security codes are hidden." -> R.string.ui_4014732450
        "Track M-Pesa and bank messages. Never sends SMS." -> R.string.ui_6a9e548360
        "Ask by voice and record when you choose. Always shows a notification." -> R.string.ui_1f5053a8b8
        "Ring at the exact minute you set" -> R.string.ui_cae0aa5360
        "Open HellFire with your assistant gesture" -> R.string.ui_5ba9cb1ad9
        "Connected accounts" -> R.string.ui_2fc385cd20
        "Your data" -> R.string.ui_ee5ef73aae
        "AI" -> R.string.ui_11fb682be0
        "Confirm payment" -> R.string.ui_a55c4ed2db
        "Save and schedule" -> R.string.ui_86b862b544
        "Save draft" -> R.string.ui_3de100106d
        "New message" -> R.string.ui_78f5975a5d
        "Save correction" -> R.string.ui_16d626ca5f
        "Forget" -> R.string.ui_a6bd489d95
        "Add to memory" -> R.string.ui_a68cace66e
        "Saved" -> R.string.ui_b5c120b316
        "Memory" -> R.string.ui_c3963aedaa
        "New note" -> R.string.ui_76ea482f84
        "My note" -> R.string.ui_db0d68b465
        "Quote from a record" -> R.string.ui_d2095df584
        "Guess to check" -> R.string.ui_e7f30ce124
        "Choose contact" -> R.string.ui_f9aee0e222
        "Choose a person" -> R.string.ui_333d798fbf
        "Name, number or email" -> R.string.ui_3b7a8a380e
        "Use typed name" -> R.string.ui_fc457b8bc2
        "Allow contacts" -> R.string.ui_54a03bcd4e
        "App settings" -> R.string.ui_d43fb8a961
        "Recovery readiness" -> R.string.ui_b16ecfd57f
        "Back up or restore" -> R.string.ui_327ff2e45d
        "What is included" -> R.string.ui_06cec1523c
        "Verify an archive without restoring…" -> R.string.ui_27d770b30f
        "Export encrypted backup…" -> R.string.ui_658cf7209a
        "Restore into empty local database…" -> R.string.ui_0a13083b8c
        "Backup passphrase · at least 12 characters" -> R.string.ui_9eb1939d74
        "Daily Google Drive backup and restore" -> R.string.ui_bfe65c4dfd
        "Import and analysis" -> R.string.ui_310a3445e7
        "Conversation monitoring" -> R.string.ui_3724061d8e
        "Originals" -> R.string.ui_cfe34edd38
        "Start monitoring" -> R.string.ui_0f4d8a4a00
        "Stop monitoring" -> R.string.ui_42608b8d42
        "Import original" -> R.string.ui_1bd255624a
        "Analyze locally" -> R.string.ui_18f2619c41
        "Play from timestamp" -> R.string.ui_79b6a7c60a
        "Stop playback" -> R.string.ui_19b81a94ee
        "Draft local summary and structured notes" -> R.string.ui_fb85598359
        "Mark summary reviewed" -> R.string.ui_7e8cc3dece
        "Manual speaker label" -> R.string.ui_64e3a65805
        "Save speaker label" -> R.string.ui_888a6a9cae
        "Export decrypted original…" -> R.string.ui_8ce17446ec
        "Delete original and transcript…" -> R.string.ui_6538629498
        "AI skills" -> R.string.ui_4131792896
        "Add skill" -> R.string.ui_bc4db5d337
        "Edit skill" -> R.string.ui_20da5974bc
        "Remove skill" -> R.string.ui_a8597db6f2
        "Remove skill?" -> R.string.ui_c07faeaa90
        "Why created" -> R.string.ui_d59b5e97bf
        "When it applies" -> R.string.ui_d18ee16647
        "Original evidence" -> R.string.ui_af537a5b7c
        "Approve and enable" -> R.string.ui_1e5f0e76e7
        "Disable" -> R.string.ui_b7e3e4aa42
        "Usage history" -> R.string.ui_460d21da0f
        "This skill has not been used." -> R.string.ui_65df5e8dd2
        "Open where used" -> R.string.ui_4ec96d2f03
        "Load older uses" -> R.string.ui_9b5fbc2474
        "Load more skills" -> R.string.ui_45d1a34591
        "Search skills" -> R.string.ui_76c02b7edd
        "Skill name" -> R.string.ui_0fbb6f49b8
        "Skill type" -> R.string.ui_bbdc102cf9
        "Related words or topic" -> R.string.ui_73dca86695
        "What AI should learn" -> R.string.ui_5519d0b203
        "Reason" -> R.string.ui_f81ab834de
        "Save and enable" -> R.string.ui_a6f9121ea2
        "Exact text to recognize" -> R.string.ui_e633f7da3f
        "Use sender or merchant from" -> R.string.ui_4803a67097
        "Everyday help" -> R.string.ui_2fc06a820b
        "Guided setup" -> R.string.ui_8ef7823ca8
        "Needs attention" -> R.string.ui_c1ebc78178
        "Privacy and storage" -> R.string.ui_ad8428d836
        "Choose what you want HellFire to help with" -> R.string.ui_dfca12ea3c
        "Capture, connections, models and backups" -> R.string.ui_927bffdac1
        "Learned rules, reasons and usage history" -> R.string.ui_ffaec44b07
        "Where your data lives" -> R.string.ui_594b837d22
        "Track spending" -> R.string.ui_938c65250f
        "Remember conversations" -> R.string.ui_534f1fbf20
        "Manage commitments" -> R.string.ui_fabb14f177
        "Choose a starting goal. You can add the other features later." -> R.string.ui_2e59be2356
        "permissions enabled" -> R.string.ui_0f62f89379
        "Enabled" -> R.string.ui_92c1cdfdf4
        "Not enabled" -> R.string.ui_2b0e804836
        "Needs your permission" -> R.string.ui_e92292cdda
        "Open setup" -> R.string.ui_06f1795b3e
        "Add an account" -> R.string.ui_e9cd49a1ea
        "Choose speech and language models" -> R.string.ui_afff2826a8
        "Check it works" -> R.string.ui_2249f06fd3
        "Open capture checks" -> R.string.ui_453769ff02
        "Connections and recovery" -> R.string.ui_fae2176e6d
        "Continue to HellFire" -> R.string.ui_27cb7a4bbd
        "Finish setup later" -> R.string.ui_cb75f40522
        "Your goal is saved. Resume from Settings › Guided setup." -> R.string.ui_7930f26852
        "Capture and permissions" -> R.string.ui_04ac3ae673
        "No delivery observed yet" -> R.string.ui_3d0c373340
        "No queued events" -> R.string.ui_61ad4c6b4a
        "Open permissions and capture" -> R.string.ui_53b18f56c1
        "Retry failed processing" -> R.string.ui_de2167ec77
        "Connections" -> R.string.ui_dc27311748
        "No connection results recorded. Optional accounts can be configured below." -> R.string.ui_982891b385
        "Models and recordings" -> R.string.ui_188947216a
        "No model download status recorded" -> R.string.ui_f1d7b951c4
        "recordings need attention" -> R.string.ui_151dfa41b4
        "Choose models" -> R.string.ui_4cded1a3ac
        "Backup and storage" -> R.string.ui_63c55b3a22
        "free on this device" -> R.string.ui_539c7230a9
        "Open backup and verification" -> R.string.ui_353a35bf2f
        "Original media" -> R.string.ui_c02afc1705
        "Database" -> R.string.ui_fa7fe67124
        "Free space" -> R.string.ui_64cd989efc
        "Manage recordings" -> R.string.ui_e17e251762
        "Manage memory" -> R.string.ui_0e62689615
        "Manage AI skills" -> R.string.ui_231a352f9c
        "Outside this phone" -> R.string.ui_cd5990faeb
        "Web copy" -> R.string.ui_b88f1c1c7d
        "Enabled. Selected records are readable on your server." -> R.string.ui_ef300be237
        "Sync is off. An earlier server copy may still exist until you delete it." -> R.string.ui_92b543463e
        "Review or delete web copy" -> R.string.ui_3fe71c22cd
        "Drive backup" -> R.string.ui_e2f9f063d4
        "Enabled. Passphrase-encrypted archives are uploaded to your Google Drive." -> R.string.ui_54d40cbf32
        "Automatic Drive backup is off. Previously exported or uploaded archives may still exist." -> R.string.ui_57a5446077
        "Manage backup archives" -> R.string.ui_663a4d4795
        "Enabled for text you explicitly submit with a cloud model. Inspect your text before sending." -> R.string.ui_fa7053a6ea
        "Off. Local answers do not use a cloud fallback." -> R.string.ui_d6d46ab953
        "Manage cloud AI" -> R.string.ui_55b3c01f53
        "Model catalog" -> R.string.ui_1b43e2d030
        "Model" -> R.string.ui_5e2c614c23
        "Choose a compatible format from a trusted publisher. No model name or download URL needs typing." -> R.string.ui_4d342fc3e4
        "Publisher and model details" -> R.string.ui_21822f8003
        "Download and use selected model" -> R.string.ui_8904d029a9
        "Pause download" -> R.string.ui_13bbdede66
        "Pause Gemma download" -> R.string.ui_4be2334779
        "Install published Gemma update" -> R.string.ui_89d9aa25f5
        "Check Gemma updates" -> R.string.ui_14ea131735
        "Not installed" -> R.string.ui_d177cdc09a
        "Imported model" -> R.string.ui_79532e47d2
        "Search memory" -> R.string.ui_2ef51f84fc
        "Load older memories" -> R.string.ui_e153865a35
        "Save with provenance" -> R.string.ui_bf01a79e61
        "Could not save. Check storage and try again." -> R.string.ui_5207b583f0
        "Skill settings saved." -> R.string.ui_abfdc338b7
        "Draft could not be saved. Keep this screen open and check free storage." -> R.string.ui_fc755767f6
        "Draft could not be opened. Check storage and device unlock." -> R.string.ui_5cd2821676
        "Remove authentication content." -> R.string.ui_a386fb65bc
        "Remove authentication content from this skill." -> R.string.ui_20f3482084
        "Remove authentication content before saving." -> R.string.ui_f140542e3c
        "Correction saved; the original source stays available." -> R.string.ui_b6d7a77f54
        "Shared content saved locally." -> R.string.ui_9e70db9e4f
        "Skills guide suggestions and local answers. They cannot send messages, post money or grant permissions." -> R.string.ui_3c4bf29b19
        "Everyday corrections create suggestions for review. Only active skills affect future decisions." -> R.string.ui_2bf9445a71
        "No skills yet. Add one, or review suggestions after correcting a payment, task or memory." -> R.string.ui_e63bdb19a9
        "This removes the skill and its usage history. Original evidence stays saved." -> R.string.ui_d8a8ef7420
        "Saved records stay on the phone. Retry capture or analysis here; sending always requires its separate approval." -> R.string.ui_32db5b80cb
        "Permission enabled is not proof of delivery. Receive a real notification or SMS, then check Capture status and its saved source. Test recording and reminder delivery on this phone." -> R.string.ui_185ae26f81
        "Captured sources, memories, skills and usage history are stored in the encrypted database. Originals and private drafts are encrypted locally." -> R.string.ui_e24a009c9b
        "Model weights and app code are separate from personal records. Original recordings remain until you explicitly delete them." -> R.string.ui_845737ba44
        "Turning a connection off does not erase copies already exported. Deletion must be requested in the relevant destination." -> R.string.ui_289403e4a5
        "Downloads wait for Wi-Fi and adequate battery/storage. Size and SHA-256 are checked, then the runtime loads the file before installation. Existing models remain until replacement succeeds." -> R.string.ui_69aecf26c6
        "Connections, models, backups and recovery" -> R.string.ui_b843ba2b33
        "Needs review" -> R.string.ui_07297fa94a
        "Disabled" -> R.string.ui_75081b593d
        "Personal guidance" -> R.string.ui_4e64d26458
        "Payment choices" -> R.string.ui_baaf7cc51f
        "Repeated false task" -> R.string.ui_c2c5eeaa19
        "Repeated false payment" -> R.string.ui_949ef2618f
        "Status" -> R.string.ui_920e413c7d
        "Revision" -> R.string.ui_2e516d68f4
        "Uses" -> R.string.ui_9e6151acb0
        "Updated" -> R.string.ui_3a5ecca188
        "Language model" -> R.string.ui_fd1c91bdd0
        "Speech model" -> R.string.ui_b2f052de67
        "Meaning search" -> R.string.ui_352d069b82
        "Choose" -> R.string.ui_c7f937836f
        "All meaningful topic words must match. Skills cannot override source facts." -> R.string.ui_e08cbb5672
        "Exact repeated text from the original source app" -> R.string.ui_3e15db6f20
        "This exact wording will not create the same task or payment suggestion again." -> R.string.ui_fdcda827b6
        "Choose a real reviewed or pending payment to identify the sender/merchant. The skill suggests choices; you still confirm each payment." -> R.string.ui_03eadbed8c
        "You chose Remember my account and category after reviewing a payment." -> R.string.ui_29970408cb
        "You confirmed this account/category while reviewing a payment. Review before applying to later matches." -> R.string.ui_6ae97e82c4
        "You marked this captured item as not a payment. An exact-match rule can avoid repeating this false suggestion." -> R.string.ui_e92877010a
        "You marked this proposal as not a task. Review this exact-match rule before using it." -> R.string.ui_efcb92495a
        "You rejected this task in Daily review. Review this rule before using it." -> R.string.ui_2d591076f6
        "You corrected this memory. Review whether the correction should guide future related answers." -> R.string.ui_17152bd73e
        "You saved this source excerpt for future reference. Review whether it should guide related answers." -> R.string.ui_ecdea8e09e
        "Review AI skills" -> R.string.ui_71e88d68b5
        "Learned from your corrections" -> R.string.ui_2e15a130e9
        "Refresh status" -> R.string.ui_442c4b8939
        "Account unavailable" -> R.string.ui_7283040535
        "Earlier versions" -> R.string.ui_401dc1a55c
        "Choose meaningful topic words." -> R.string.ui_b7c60e8681
        "Choose an existing account." -> R.string.ui_4aae80751d
        "Could not save the complete share. Already saved items are retained; retry resumes this share and skips completed files. Re-share files if Android access expired." -> R.string.ui_a4861b29ef
        "Check file access and storage." -> R.string.ui_bbace8ab59
        "Processing scheduled; check the updated queue. No messages were sent." -> R.string.ui_3c7092c624
        "Could not schedule processing. Saved records are retained." -> R.string.ui_24b595f85f
        "Privacy settings could not be read. Nothing has changed." -> R.string.ui_eb5bb0e600
        "Loading privacy settings…" -> R.string.ui_9f2467da31
        "Retry" -> R.string.ui_942087cc2d
        "Player" -> R.string.ui_64aee8c6cb
        "Unlock" -> R.string.ui_4ac709aa58
        "Lock screen" -> R.string.ui_f59d9abe42
        "Original is not available." -> R.string.ui_d47f85a04c
        "Notifications are off, so lock-screen controls may be hidden by Android." -> R.string.ui_77d99701fc
        "Thumbnail unavailable" -> R.string.ui_7f7739d908
        "Pause" -> R.string.ui_858e4ba7a2
        "Play" -> R.string.ui_436e61016e
        "Skip back" -> R.string.ui_2876c1f5c3
        "Skip forward" -> R.string.ui_3284049794
        "Previous" -> R.string.ui_a57b08a480
        "Next" -> R.string.ui_1ff57a29d7
        "Exit full screen" -> R.string.ui_897e0cf78b
        "Full screen" -> R.string.ui_674fe2acd0
        "Picture-in-picture" -> R.string.ui_3baa2d6ed4
        "Speed" -> R.string.ui_c372fee9b4
        "Shuffle" -> R.string.ui_317cb3a7ef
        "Unfavorite" -> R.string.ui_5e87a7bd5a
        "Favorite" -> R.string.ui_ea713ecd85
        "Sleep timer" -> R.string.ui_336c84d8da
        "End of item" -> R.string.ui_bfb99f7ddb
        "Set A" -> R.string.ui_7781d909b9
        "Set B" -> R.string.ui_bf8f310bda
        "Clear loop" -> R.string.ui_9d10c311ca
        "Save range" -> R.string.ui_67eaebc39f
        "Previous chapter" -> R.string.ui_2bebdca31f
        "Next chapter" -> R.string.ui_4344dde797
        "Search in this item" -> R.string.ui_b318882ba8
        "Indexed search" -> R.string.ui_bf1b7b8a4c
        "Bookmark" -> R.string.ui_1bac400788
        "Seek" -> R.string.ui_67ae3405bc
        "Hide" -> R.string.ui_ac20a57bfd
        "Delete" -> R.string.ui_e2d0a54968
        "Convert to manual" -> R.string.ui_8bb41c4164
        "Remove" -> R.string.ui_c3812fc4ac
        "Transcript" -> R.string.ui_721164f0dc
        "Copy" -> R.string.ui_e21f935f11
        "Correct" -> R.string.ui_aca01a1a3a
        "Playback history" -> R.string.ui_d34349bbcc
        "No playback history yet." -> R.string.ui_5484a3a7f5
        "Title" -> R.string.ui_7e8cd2056d
        "Notes" -> R.string.ui_8a7525b149
        "Save bookmark" -> R.string.ui_b63ed49a38
        "Close" -> R.string.ui_7d9eb7acb1
        "Volume" -> R.string.ui_b10fb966d7
        "Brightness" -> R.string.ui_ec811d30a8
        "No extra audio or subtitle tracks reported." -> R.string.ui_eeed1164fe
        "Audio track" -> R.string.ui_e4a8479838
        "Subtitles off" -> R.string.ui_6a5216300c
        "No bookmarks or chapters yet." -> R.string.ui_b284a23c3c
        "Open" -> R.string.ui_ed077f3d81
        "System overlay is not available. Picture-in-picture is the supported floating player." -> R.string.ui_afd6047384
        "Playback session could not start." -> R.string.ui_db9061fce2
        "Storage permission was not granted. The external file was not opened." -> R.string.ui_fa1d54ecfb
        "Picture-in-picture is not available on this device." -> R.string.ui_09da75e9c7
        "Picture-in-picture was refused by this device. Playback continues in the app." -> R.string.ui_1198f84a2f
        "Volume control was refused by this device." -> R.string.ui_e14509c412
        "Waveform not available for this file. Playback can still continue." -> R.string.ui_554d3009ce
        "No indexed match. Analysis was not started." -> R.string.ui_ca416eb41e
        "This file cannot be played on this device. The codec or container is not supported. The original was not modified." -> R.string.ui_c730e1984e
        "The original could not be read. Nothing was written out as a decrypted file." -> R.string.ui_b84bfebce5
        "Playback failed. The original was not modified." -> R.string.ui_2cb0daa2e0
        "This item is not audio or video." -> R.string.ui_1a00dd5a8a
        "This external item has no content URI." -> R.string.ui_f0828a29ee
