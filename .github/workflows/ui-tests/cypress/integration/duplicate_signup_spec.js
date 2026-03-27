/**
 * Duplicate signup flow
 *
 * Verifies that attempting to create a user with an existing username
 * surfaces an inline error on the signup form.
 */

describe('Signup shows inline error for duplicate username', function() {
  const password = 'bells'
  const firstName = 'Tom'
  const lastName = 'Nook'
  const uuid = () => Cypress._.random(0, 1e6)

  it('shows conflict error when username is already taken', function() {
    const id = uuid()
    const user = {
      username: `dup_user_${id}`,
      firstName: firstName,
      lastName: `${lastName}-${id}`,
      password: password,
    }

    // First signup should succeed and redirect to /home
    cy.createAccount(user)
    cy.url().should('include', '/home')

    // Logout to return to login/signup flow
    cy.get('#logoutForm').submit()
    cy.url().should('include', '/login')

    // Second signup attempt with same username should stay on /signup
    cy.createAccount(user)
    cy.url().should('include', '/signup')

    // Inline error should be visible and mention username conflict
    cy.get('#alertBanner').should('be.visible')
    cy.get('.invalid-feedback')
      .should('be.visible')
      .contains('Username already in use')
  })
})