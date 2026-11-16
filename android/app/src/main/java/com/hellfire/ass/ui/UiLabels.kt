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
        else -> null
    }
}
