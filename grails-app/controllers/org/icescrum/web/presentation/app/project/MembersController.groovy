/*
 * Copyright (c) 2010 iceScrum Technologies.
 *
 * This file is part of iceScrum.
 *
 * iceScrum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * iceScrum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with iceScrum.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors:
 *
 * Stéphane Maldini (stephane.maldini@icescrum.com)
 * Vincent Barrier (vbarrier@kagilum.com)
 *
 */

package org.icescrum.web.presentation.app.project

import grails.converters.JSON
import grails.plugins.springsecurity.Secured
import org.icescrum.core.domain.Product
import org.icescrum.core.domain.Team
import org.icescrum.core.domain.User
import org.icescrum.core.utils.BundleUtils
import org.icescrum.core.domain.security.Authority
import grails.plugin.springcache.annotations.Cacheable
import grails.plugin.springcache.annotations.CacheFlush

@Secured('isAuthenticated() and (stakeHolder() or inProduct() or owner())')
class MembersController {

    def springSecurityService
    def teamService
    def productService
    def securityService
    def springcacheService

    @Cacheable(cache = "projectMembersCache", cacheResolver = "projectCacheResolver", keyGenerator= 'roleAndLocaleKeyGenerator')
    def edit = {
        def product = Product.get(params.product)
        def members = allMembersProduct(product)
        def ownerSelect = product.owner

        def possibleOwners = members.clone()

        ownerSelect = [name: ownerSelect.firstName+' '+ownerSelect.lastName,
                         activity:ownerSelect.preferences.activity?:'&nbsp;',
                         id: ownerSelect.id,
                         avatar:is.avatar(user:ownerSelect,link:true)]

        if (!possibleOwners*.id.contains(ownerSelect.id)){
            possibleOwners.add(ownerSelect)
        }

        def listRoles = product.preferences.hidden ? 'roles' : 'rolesPublic'
        render(template: "dialogs/members", model: [product: product,
                                                    members: members,
                                                    ownerSelect:ownerSelect,
                                                    possibleOwners:possibleOwners,
                                                    user: springSecurityService.currentUser,
                                                    rolesNames:BundleUtils."${listRoles}".values().collect {v -> message(code: v)},
                                                    rolesKeys:BundleUtils."${listRoles}".keySet().asList()])
    }

    @Secured(['owner() or scrumMaster()', 'RUN_AS_PERMISSIONS_MANAGER'])
    @CacheFlush(caches = ['projectMembersCache','projectTemplateCache'], cacheResolver = 'projectCacheResolver')
    def update = {
        def product = Product.get(params.product)
        def team = Team.get(product.teams.asList()[0].id)
        def currentMembers = allMembersProduct(product)
        try{
            def idmembers = []
            params.members?.each{ k,v ->
                    def u = User.get(v.toLong())
                    def found = currentMembers.find{ it.id == u.id}
                    if (found){
                        if (found.role.toString() != params.role."${k}"){
                            productService.changeRole(product,team,u,Integer.parseInt(params.role."${k}"))
                        }
                    }else{
                        productService.addRole(product,team,u,Integer.parseInt(params.role."${k}"))
                    }
                idmembers << u.id
            }
            def commons = currentMembers*.id.intersect(idmembers)
            def difference = currentMembers*.id.plus(commons)
            difference.removeAll(commons)
            difference?.each{
                def found = currentMembers.find{ it2 -> it == it2.id}
                def u = User.get(found.id)
                productService.removeRole(product,team,u,found.role)
            }

            if (params.creator && params.creator?.toLong() != product.owner.id){
                securityService.changeOwner(User.get(params.creator.toLong()),product)
            }
            render (status:200)
        }catch(RuntimeException re){
            if (log.debugEnabled) re.printStackTrace()
            render(status: 400, contentType: 'application/json', text: [notice: [text: message(code: 'is.team.error.not.saved')]] as JSON)
        }
    }

    @Secured('inProduct() or stakeHolder()')
    @CacheFlush(caches = ['projectMembersCache','projectTemplateCache'], cacheResolver = 'projectCacheResolver')
     def leaveTeam = {
        def product = Product.get(params.product)
        def user = springSecurityService.currentUser
        def team = Team.get(product.teams.asList()[0].id)
        def currentMembers = allMembersProduct(product)
        try {
            def found = currentMembers.find{ it.id == user.id}
            def u = User.get(found.id)
            productService.removeRole(product,team,u,found.role)
            flushCache(cacheResolver:'projectCacheResolver', cache:'projectMembersCache')
            render(status: 200, contentType: 'application/json', text: [url: createLink(uri: '/')] as JSON)
        } catch (e) {
            render(status: 400, contentType: 'application/json', text: [notice: [text: renderErrors(bean: team)]] as JSON)
        }
    }

    private List allMembersProduct(def product) {
        def team = product.teams.asList().first()
        def productOwners = product.productOwners
        def scrumMasters = team.scrumMasters
        def members = []

        team.members?.each{
            def role = Authority.MEMBER
            if (scrumMasters*.id?.contains(it.id) && productOwners*.id?.contains(it.id)){
                role = Authority.PO_AND_SM
            }
            else if(scrumMasters*.id?.contains(it.id)){
                role = Authority.SCRUMMASTER
            }
            else if(productOwners*.id?.contains(it.id)){
                role = Authority.PRODUCTOWNER
            }
            members.add([name: it.firstName+' '+it.lastName,
                         activity:it.preferences.activity?:'&nbsp;',
                         id: it.id,
                         avatar:is.avatar(user:it,link:true),
                         role: role])
        }

        productOwners?.each{
            if(!members*.id?.contains(it.id)){
                members.add([name: it.firstName+' '+it.lastName,
                         activity:it.preferences.activity?:'&nbsp;',
                         id: it.id,
                         avatar:is.avatar(user:it,link:true),
                         role: Authority.PRODUCTOWNER])
            }
        }

        product.stakeHolders?.each{
            members.add([name: it.firstName+' '+it.lastName,
                         activity:it.preferences.activity?:'&nbsp;',
                         id: it.id,
                         avatar:is.avatar(user:it,link:true),
                         role: Authority.STAKEHOLER])
        }
        members.sort{ a,b -> a.role > b.role ? -1 : 1  }
    }
}