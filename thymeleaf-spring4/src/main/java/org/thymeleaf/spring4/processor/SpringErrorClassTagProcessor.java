/*
 * =============================================================================
 * 
 *   Copyright (c) 2011-2014, The THYMELEAF team (http://www.thymeleaf.org)
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 * =============================================================================
 */
package org.thymeleaf.spring4.processor;

import java.util.Arrays;

import org.springframework.web.servlet.support.BindStatus;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.context.IProcessingContext;
import org.thymeleaf.context.ITemplateProcessingContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.engine.AttributeNames;
import org.thymeleaf.engine.IElementStructureHandler;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.spring4.naming.SpringContextVariableNames;
import org.thymeleaf.spring4.util.FieldUtils;
import org.thymeleaf.standard.expression.IStandardExpression;
import org.thymeleaf.standard.expression.IStandardExpressionParser;
import org.thymeleaf.standard.expression.StandardExpressions;
import org.thymeleaf.standard.expression.VariableExpression;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.util.StringUtils;
import org.unbescape.html.HtmlEscape;

/**
 * Adds the given class to the field on which this attribute is applied, if that
 * field contains errors.  It's similar to a combination of <tt>th:classappend</tt>
 * with a <tt>${#fields.hasErrors()}</tt> expression.
 * 
 * @author Daniel Fern&aacute;ndez
 * @since 3.0.0
 */
public final class SpringErrorClassTagProcessor extends AbstractAttributeTagProcessor {

    public static final int ATTR_PRECEDENCE = 1500;
    public static final String ATTR_NAME = "errorclass";
    public static final String TARGET_ATTR_NAME = "class";



    public SpringErrorClassTagProcessor() {
        super(TemplateMode.HTML, null, false, ATTR_NAME, true,ATTR_PRECEDENCE);
    }




    @Override
    protected final void doProcess(
            final ITemplateProcessingContext processingContext,
            final IProcessableElementTag tag,
            final AttributeName attributeName, final String attributeValue,
            final IElementStructureHandler structureHandler) {

        final BindStatus bindStatus = computeBindStatus(processingContext, tag);
        if (bindStatus == null) {
            final AttributeName fieldAttributeName =
                    AttributeNames.forHTMLName(getDialectPrefix(), AbstractSpringFieldTagProcessor.ATTR_NAME);
            throw new TemplateProcessingException(
                    "Cannot apply \"" + attributeName + "\": this attribute requires the existence of " +
                    "a \"name\" (or " + Arrays.asList(fieldAttributeName.getCompleteAttributeNames()) + ") attribute " +
                    "with non-empty value in the same host tag.");
        }

        if (bindStatus.isError()) {

            final IEngineConfiguration configuration = processingContext.getConfiguration();
            final IStandardExpressionParser expressionParser = StandardExpressions.getExpressionParser(configuration);

            final IStandardExpression expression = expressionParser.parseExpression(processingContext, attributeValue);
            final Object expressionResult = expression.execute(processingContext);

            final String newAttributeValue = HtmlEscape.escapeHtml4Xml(expressionResult == null ? null : expressionResult.toString());

            // If we are not adding anything, we'll just leave it untouched
            if (newAttributeValue != null && newAttributeValue.length() > 0) {

                if (!tag.getAttributes().hasAttribute(TARGET_ATTR_NAME) ||
                        tag.getAttributes().getValue(TARGET_ATTR_NAME).length() == 0) {
                    // No previous value, so it's just a replacement
                    tag.getAttributes().setAttribute(TARGET_ATTR_NAME, newAttributeValue);
                } else {
                    final String currentValue = tag.getAttributes().getValue(TARGET_ATTR_NAME);
                    tag.getAttributes().setAttribute(TARGET_ATTR_NAME, currentValue + ' ' + newAttributeValue);
                }

            }

        }

        tag.getAttributes().removeAttribute(attributeName);

    }




    /*
     * There are two scenarios for a th:errorclass to appear in: one is in an element for which a th:field has already
     * been executed, in which case we already have a BindStatus to check for errors; and the other one is an element
     * for which a th:field has not been executed, but which should have a "name" attribute (either directly or as
     * the result of executing a th:name) -- in this case, we'll have to build the BuildStatus ourselves.
     */
    private static BindStatus computeBindStatus(final IProcessingContext processingContext, final IProcessableElementTag tag) {

        /*
         * First, try to obtain an already-existing BindStatus resulting from the execution of a th:field attribute
         * in the same element.
         */
        final BindStatus bindStatus =
                (BindStatus) processingContext.getVariablesMap().getVariable(SpringContextVariableNames.SPRING_FIELD_BIND_STATUS);
        if (bindStatus != null) {
            return bindStatus;
        }

        /*
         * It seems no th:field was executed on the same element, so we must rely on the "name" attribute (probably
         * specified by hand or by a th:name). No th:field was executed, so no BindStatus available -- we'll have to
         * build it ourselves.
         */
        final String fieldName = tag.getAttributes().getValue("name");
        if (StringUtils.isEmptyOrWhitespace(fieldName)) {
            return null;
        }

        final VariableExpression boundExpression =
                (VariableExpression) processingContext.getVariablesMap().getVariable(SpringContextVariableNames.SPRING_BOUND_OBJECT_EXPRESSION);

        if (boundExpression == null) {
            // No bound expression, so just use the field name
            return FieldUtils.getBindStatusFromParsedExpression(processingContext, false, fieldName);
        }

        // Bound object and field object names might intersect (e.g. th:object="a.b", name="b.c"), and we must compute
        // the real 'bindable' name ("a.b.c") by only using the first token in the bound object name, appending the
        // rest of the field name: "a" + "b.c" -> "a.b.c"
        final String boundExpressionStr = boundExpression.getExpression();
        final String computedFieldName;
        if (boundExpressionStr.indexOf('.') == -1) {
            computedFieldName = boundExpressionStr + '.' + fieldName; // we append because we will use no form root afterwards
        } else {
            computedFieldName = boundExpressionStr.substring(0, boundExpressionStr.indexOf('.')) + '.' + fieldName;
        }

        // We set "useRoot" to false because we have already computed that part
        return FieldUtils.getBindStatusFromParsedExpression(processingContext, false, computedFieldName);

    }


}