package dev.isylxnt.duskcontracts.inventory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

final class MenuLayoutResourceTest {
    @Test
    void bundledMenusMatchTheRequestedNavigationAndIcons() throws IOException {
        try (var input = MenuLayoutResourceTest.class.getResourceAsStream("/menus.yml")) {
            assertThat(input).as("bundled menus.yml").isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(yaml)
                    .contains("hub:", "title: '<dark_gray>Dusk Contracts'", "size: 27")
                    .contains("library: {slot: 10, material: BOOKSHELF")
                    .contains("book: {slot: 13, material: BOOK")
                    .contains("create: {slot: 16, material: EMERALD")
                    .contains("claims: {slot: 22, material: CHEST")
                    .contains("creation-type:", "assassination: {slot: 12, material: DIAMOND_SWORD", "delivery: {slot: 14, material: GRASS_BLOCK")
                    .contains("creation-assassination:", "target: {slot: 11, material: PLAYER_HEAD")
                    .contains("creation-assassination-confirm:", "reward: {slot: 12, material: EMERALD")
                    .contains("start: {slot: 31, material: LIME_DYE", "started: {slot: 31, material: LIME_DYE")
                    .contains("cancel: {slot: 40, material: RED_DYE", "cancel-confirm: {slot: 40, material: RED_DYE, glow: true")
                    .contains("contributions: {slot: 11, material: WRITTEN_BOOK, hide-item-specifics: true")
                    .contains("mine: {slot: 15, material: KNOWLEDGE_BOOK")
                    .contains("back: {slot: 18, material: BARRIER")
                    .contains("creation:", "title: '<dark_gray>Creation'", "size: 27")
                    .contains("material: {slot: 10, name:")
                    .contains("amount: {slot: 11, name:")
                    .contains("matching: {slot: 13, material: REDSTONE")
                    .contains("duration: {slot: 14, material: CLOCK")
                    .contains("reward: {slot: 15, material: EMERALD")
                    .contains("visibility: {slot: 16, material: BOOK")
                    .contains("continue: {slot: 26, material: LIME_DYE")
                    .contains("creation-time:", "option-slots: [10, 11, 12, 14, 15, 16]")
                    .contains("selected-name: '<green>{duration} — SELECTED'")
                    .contains("creation-reward:", "title: '<dark_gray>Reward type'")
                    .contains("money: {slot: 11, material: EMERALD")
                    .contains("items: {slot: 15, material: CHEST")
                    .contains("creation-reward-items:", "title: '<dark_gray>Item reward'", "size: 54")
                    .contains("accept: {slot: 53, material: LIME_DYE")
                    .contains("creation-confirm:", "settings: {slot: 13, material: BOOK")
                    .contains("accept: {slot: 15, material: LIME_DYE")
                    .contains("back: {slot: 16, material: RED_DYE")
                    .contains("confirm: {slot: 53, material: LIME_DYE")
                    .contains("cancel: {slot: 45, material: BARRIER")
                    .contains("info: {slot: 49, material: WRITABLE_BOOK")
                    .contains("navigation: {slot: 53, material: ARROW")
                    .contains("material: GOLD_INGOT");
        }
    }
}
