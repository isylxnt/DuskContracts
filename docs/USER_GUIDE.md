# Player guide

Open the compact home screen with `/contract` or `/contracts`. The bookshelf opens the public Library, the center book opens your Created and Participating listings, and the emerald starts creation of a funded reward. Use `/contracts claim` to collect pending rewards, deliveries, or refunds.

## Create

Run `/contracts create` and choose Item delivery or Assassination. The editor is graphical. Clicking material, amount, reward amount or bounty target temporarily asks for a value in chat; type `cancel` to return. Item rewards are deposited into a protected chest and remain in plugin custody until creation succeeds or is cancelled. Shift-click and normal inventory clicks are supported. If returned items do not fit, they are dropped at the player's current location.

`MATERIAL` accepts the same material. `SIMILAR` also checks normal metadata. `EXACT` compares the stable public serialized item data. Item rewards always require one complete delivery. Money rewards may use proportional delivery, in which each contributor receives an exact cumulative share and the final contributor receives the legitimate remainder.

## Browse and deliver

Use the bookshelf or `/contracts browse` to open the public Library. Every gold ingot represents a listing; click it for details. The writable book explains the screen, the barrier returns to the main menu, and the arrow uses left-click for the next page and right-click for the previous page. `/contracts search STONE`, `/contracts search PlayerName`, and `/contracts info ID` narrow the view. Directed contracts are visible only to the selected player and do not appear in the public Library.

Choose an open contract, click Deliver, place items in the top deposit area, and confirm. The plugin checks the live database version and every item again. If another player changed the contract, your deposit is returned safely.

## Assassination contracts

Choose the sword during creation, select any player who has joined the server—including yourself as the bounty target—then configure duration and reward. A hunter must open the public listing and click Start before the kill can complete it. The active book shows started bounties. Only a direct Bukkit-attributed player killer qualifies; environmental deaths and suicides do not. A persistent configurable killer/target cooldown prevents two accounts from repeatedly farming newly-created bounties. Operators can bypass it with `duskcontracts.assassination.bypass-farming`.

## Claims and cancellation

Use `/contracts claim` and click one claim at a time. If an inventory cannot hold the items or an economy provider rejects a payment, the claim stays pending. A repeated click cannot pay the same claim twice.

Creators can use `/contracts cancel ID` or the red dye on the listing detail screen. The GUI requires two clicks. Contributions already committed remain valid; only unused escrow is returned. Expiration behaves the same way.
